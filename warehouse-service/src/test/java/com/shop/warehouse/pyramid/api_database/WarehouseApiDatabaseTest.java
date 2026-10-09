package com.shop.warehouse.pyramid.api_database;

import com.shop.warehouse.delivery.DeliveryModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Проверяет настоящие MVC-контроллеры и зафиксированные записи PostgreSQL своего сервиса.
 * MockMvc выполняет маршрутизацию, преобразование тела и обработку ошибок без TCP-сервера.
 * Схему создаёт production Flyway в отдельном Testcontainers; Compose и пользовательские базы не используются.
 * Общего rollback теста нет: SQL-подготовка и HTTP-транзакции завершаются до независимого чтения.
 */
@Tag("api-database")
@Testcontainers
@WebAppConfiguration
@SpringJUnitConfig(WarehouseApiDatabaseConfig.class)
class WarehouseApiDatabaseTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private Clock clock;
    private MockMvc mvc;
    private String storeId;
    private String deliveryId;
    private static final Instant RECEIVED = Instant.parse("2026-10-09T11:00:00Z");
    /** Создаёт отдельный магазин и ожидающую расчёта поставку с одной строкой без приёма Kafka-события. */
    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build(); storeId="S-"+UUID.randomUUID(); deliveryId="D-"+UUID.randomUUID();
        jdbc.update("INSERT INTO stores(store_id,city,delivery_sequence) VALUES(?,'MOSCOW',1)",storeId);
        jdbc.update("INSERT INTO deliveries(store_id,delivery_id,delivery_sequence,fingerprint,state,received_at,next_attempt_at,original_payload) VALUES(?,?,1,?,'WAITING_PRICING',?,?,?::jsonb)",storeId,deliveryId,"b".repeat(64),Timestamp.from(RECEIVED),Timestamp.from(clock.instant().plusSeconds(3600)),"{}");
        jdbc.update("INSERT INTO delivery_items(store_id,delivery_id,line_id,product_id,product_type,short_name,description,quantity,purchase_price,currency) VALUES(?,?,'L-1','P-1','NON_FOOD','Мыло','Описание',10,100.00,'RUB')",storeId,deliveryId);
    }
    /** Удаляет только свою поставку и магазин в порядке внешних ключей; миграционные fixtures сохраняются. */
    @AfterEach void cleanup() {
        for(String table:List.of("warehouse_outbox","received_events","delivery_items","deliveries","stores")) jdbc.update("DELETE FROM "+table+" WHERE store_id=?",storeId);
    }

    /**
     * WH-API-DB-001. SQL фиксирует ожидающую поставку и одну строку. GET возвращает владельца, даты, состояние и точные денежные значения.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-001: подготовленная поставка читается со всеми исходными полями")
    void readsPreparedDelivery() throws Exception {
        View view=read(mvc.perform(get(path())).andExpect(status().isOk()).andReturn());
        assertThat(view.storeId()).isEqualTo(storeId); assertThat(view.deliveryId()).isEqualTo(deliveryId);
        assertThat(view.deliverySequence()).isEqualTo(1); assertThat(view.state()).isEqualTo("WAITING_PRICING");
        assertThat(view.receivedAt()).isEqualTo(RECEIVED); assertThat(view.postedAt()).isNull(); assertThat(view.attemptCount()).isZero();
        assertThat(view.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(3600)); assertThat(view.lastError()).isNull();
        assertThat(view.items()).containsExactly(new Line("L-1","P-1","NON_FOOD","Мыло","Описание",10,"100.00","RUB",null,null,null,null));
    }

    /**
     * WH-API-DB-002. POST переносит время следующей попытки на управляемое текущее время; состояние и число попыток сохраняются.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-002: повтор расчёта фиксирует немедленную попытку в базе данных")
    void schedulesRetry() throws Exception {
        View view=read(mvc.perform(post(path()+"/retry-pricing")).andExpect(status().isAccepted()).andReturn());
        assertThat(nextAttempt()).isEqualTo(clock.instant()); assertThat(view.nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(view.state()).isEqualTo("WAITING_PRICING"); assertThat(view.attemptCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM deliveries WHERE store_id=? AND delivery_id=?",Long.class,storeId,deliveryId)).isZero();
    }

    /**
     * WH-API-DB-003. GET неизвестной поставки возвращает NOT_FOUND; число своих строк не меняется.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-003: чтение отсутствующей поставки не создаёт запись")
    void rejectsMissingRead() throws Exception {
        mvc.perform(get("/stores/"+storeId+"/deliveries/D-missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE store_id=?",Integer.class,storeId)).isEqualTo(1);
    }

    /**
     * WH-API-DB-004. POST для неизвестной поставки возвращает NOT_FOUND и не создаёт новую попытку.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-004: повтор отсутствующей поставки не создаёт запись")
    void rejectsMissingRetry() throws Exception {
        mvc.perform(post("/stores/"+storeId+"/deliveries/D-missing/retry-pricing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE store_id=?",Integer.class,storeId)).isEqualTo(1);
        assertThat(nextAttempt()).isEqualTo(clock.instant().plusSeconds(3600));
    }

    /**
     * WH-API-DB-005. SQL переводит поставку в POSTED. POST повторного расчёта возвращает конфликт и не меняет состояние и дату.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-005: повтор уже оприходованной поставки отклоняется без изменения")
    void rejectsPostedRetry() throws Exception {
        jdbc.update("UPDATE deliveries SET state='POSTED',posted_at=?,next_attempt_at=NULL WHERE store_id=? AND delivery_id=?",Timestamp.from(clock.instant()),storeId,deliveryId);
        mvc.perform(post(path()+"/retry-pricing")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_WAITING_PRICING"));
        assertThat(jdbc.queryForObject("SELECT state FROM deliveries WHERE store_id=? AND delivery_id=?",String.class,storeId,deliveryId)).isEqualTo("POSTED");
        View view=read(mvc.perform(get(path())).andExpect(status().isOk()).andReturn());
        assertThat(view.postedAt()).isEqualTo(clock.instant()); assertThat(view.nextAttemptAt()).isNull();
    }

    /**
     * WH-API-DB-006. SQL фиксирует токен и срок активной попытки. POST меняет расписание, но не снимает чужую аренду.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-006: повтор расчёта сохраняет действующую аренду попытки")
    void preservesLeaseOnRetry() throws Exception {
        UUID token=UUID.randomUUID(); Instant until=clock.instant().plusSeconds(30);
        jdbc.update("UPDATE deliveries SET lease_token=?,lease_until=? WHERE store_id=? AND delivery_id=?",token,Timestamp.from(until),storeId,deliveryId);
        mvc.perform(post(path()+"/retry-pricing")).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT lease_token FROM deliveries WHERE store_id=? AND delivery_id=?",UUID.class,storeId,deliveryId)).isEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT lease_until FROM deliveries WHERE store_id=? AND delivery_id=?",Timestamp.class,storeId,deliveryId).toInstant()).isEqualTo(until);
        assertThat(nextAttempt()).isEqualTo(clock.instant());
    }

    /**
     * WH-API-DB-007. SQL меняет количество с 10 на 3 и цену на 105.25. GET должен вернуть оба новых значения.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-007: прямое изменение строки поставки видно при чтении")
    void readsDirectUpdate() throws Exception {
        jdbc.update("UPDATE delivery_items SET quantity=3,purchase_price=105.25 WHERE store_id=? AND delivery_id=?",storeId,deliveryId);
        View view=read(mvc.perform(get(path())).andExpect(status().isOk()).andReturn());
        assertThat(view.items()).containsExactly(new Line("L-1","P-1","NON_FOOD","Мыло","Описание",3,"105.25","RUB",null,null,null,null));
    }

    /**
     * WH-API-DB-008. SQL удаляет строку состава и поставку. GET возвращает NOT_FOUND вместо старого состояния.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-008: прямое удаление поставки делает её недоступной")
    void rejectsDirectDelete() throws Exception {
        jdbc.update("DELETE FROM delivery_items WHERE store_id=? AND delivery_id=?",storeId,deliveryId);
        jdbc.update("DELETE FROM deliveries WHERE store_id=? AND delivery_id=?",storeId,deliveryId);
        mvc.perform(get(path())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * WH-API-DB-009. GET с другим владельцем возвращает NOT_FOUND. Исходная поставка своего магазина остаётся доступной.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("WH-API-DB-009: поставка чужого магазина не раскрывается")
    void rejectsOtherStore() throws Exception {
        mvc.perform(get("/stores/S-other/deliveries/"+deliveryId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(read(mvc.perform(get(path())).andExpect(status().isOk()).andReturn()).storeId()).isEqualTo(storeId);
    }
    /** Возвращает путь диагностики именно своей поставки. */
    private String path() { return "/stores/"+storeId+"/deliveries/"+deliveryId; }
    /** Разбирает типизированное состояние поставки; сравнения выполняются явно в сценарии. */
    private View read(MvcResult result) throws Exception { return mapper.readValue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),View.class); }
    /** Читает зафиксированное время следующей попытки напрямую из PostgreSQL. */
    private Instant nextAttempt() { return jdbc.queryForObject("SELECT next_attempt_at FROM deliveries WHERE store_id=? AND delivery_id=?",Timestamp.class,storeId,deliveryId).toInstant(); }

}
