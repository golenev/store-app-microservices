package com.shop.store.pyramid.api_database;

import com.shop.store.dto.Cart;
import com.shop.store.dto.CartLine;
import com.shop.store.dto.Catalog;
import com.shop.store.dto.PutItem;
import com.shop.store.dto.Stock;
import com.shop.store.dto.Submission;
import com.shop.store.dto.SubmitInput;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет настоящие MVC-контроллеры и зафиксированные записи PostgreSQL своего сервиса.
 * MockMvc выполняет маршрутизацию, преобразование тела и обработку ошибок без TCP-сервера.
 * Схему создаёт production Flyway в отдельном Testcontainers; Compose и пользовательские базы не используются.
 * Общего rollback теста нет: SQL-подготовка и HTTP-транзакции завершаются до независимого чтения.
 */
@Tag("api-database")
@Testcontainers
@WebAppConfiguration
@SpringJUnitConfig(StoreApiDatabaseConfig.class)
class StoreApiDatabaseTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private Clock clock;
    private MockMvc mvc;
    private String storeId;
    private UUID cartId;
    private UUID stockId;
    /** Подготавливает отдельный магазин, поставку и десять единиц товара по 120.00 рублей без Kafka. */
    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        storeId = "S-" + UUID.randomUUID(); cartId = UUID.randomUUID(); stockId = UUID.randomUUID();
        jdbc.update("INSERT INTO store_scopes VALUES(?)", storeId);
        var now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO stock_receipts(store_id,delivery_id,delivery_sequence,fingerprint,received_at,posted_at,applied_at,payload) VALUES(?, 'D-1',1,?,?,?,?,?)",
                storeId, "a".repeat(64), now, now, now, "{}");
        jdbc.update("INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence) VALUES(?,?,'P-1','NON_FOOD','Мыло','Описание',120.00,'RUB',10,1)", stockId, storeId);
    }
    /** Удаляет только свой магазин в порядке внешних ключей; схема и миграционные fixtures сохраняются. */
    @AfterEach void cleanup() {
        for (String table : List.of("store_outbox", "stock_expenses", "submissions", "cart_items", "carts", "stock_movements", "inventory", "processed_events", "stock_receipts", "store_scopes"))
            jdbc.update("DELETE FROM " + table + " WHERE store_id=?", storeId);
    }

    /**
     * STORE-API-DB-001. В магазине есть остаток. POST создаёт одну независимую пустую корзину; SQL и GET подтверждают сохранённое состояние.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-001: созданная корзина сохраняется открытой с версией 0")
    void createsCart() throws Exception {
        var response = mvc.perform(post("/stores/" + storeId + "/carts")).andExpect(status().isCreated()).andReturn();
        Cart created = read(response, Cart.class);
        assertThat(created.storeId()).isEqualTo(storeId);
        assertThat(created.version()).isZero(); assertThat(created.state()).isEqualTo("OPEN");
        assertThat(created.items()).isEmpty(); assertThat(created.totalAmount()).isEqualTo("0.00");
        assertThat(response.getResponse().getHeader("Location")).isEqualTo("/stores/" + storeId + "/carts/" + created.cartId());
        assertThat(jdbc.queryForObject("SELECT version FROM carts WHERE store_id=? AND cart_id=?",Long.class,storeId,created.cartId())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM carts WHERE store_id=? AND cart_id=?",String.class,storeId,created.cartId())).isEqualTo("OPEN");
        assertThat(read(mvc.perform(get("/stores/"+storeId+"/carts/"+created.cartId())).andExpect(status().isOk()).andReturn(), Cart.class)).isEqualTo(created);
    }

    /**
     * STORE-API-DB-002. SQL фиксирует корзину и две единицы товара. GET должен вернуть все поля позиции и сумму 240.00.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-002: корзина из SQL читается с позицией и точной суммой")
    void readsPreparedCart() throws Exception {
        insertCart(); insertLine(2);
        Cart cart = read(mvc.perform(get(cartPath())).andExpect(status().isOk()).andReturn(), Cart.class);
        assertThat(cart.storeId()).isEqualTo(storeId); assertThat(cart.cartId()).isEqualTo(cartId);
        assertThat(cart.version()).isZero(); assertThat(cart.state()).isEqualTo("OPEN");
        assertThat(cart.items()).containsExactly(new CartLine(stockId,"P-1","Мыло",2,"120.00","240.00"));
        assertThat(cart.totalAmount()).isEqualTo("240.00"); assertThat(cart.currency()).isEqualTo("RUB"); assertThat(cart.submissionId()).isNull();
    }

    /**
     * STORE-API-DB-003. PUT добавляет три единицы в корзину версии 0. SQL подтверждает количество, версию 1 и прежний остаток 10.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-003: изменение количества позиции фиксируется без резервирования остатка")
    void updatesLine() throws Exception {
        insertCart();
        Cart cart = read(mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(3,0)))).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.version()).isEqualTo(1); assertThat(cart.totalAmount()).isEqualTo("360.00");
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items WHERE cart_id=? AND stock_item_id=?",Integer.class,cartId,stockId)).isEqualTo(3);
        assertThat(cartVersion()).isEqualTo(1); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-004. DELETE удаляет подготовленную позицию. Проверяем пустой ответ корзины, отсутствие строки и неизменный остаток.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-004: удаление позиции фиксируется с однократным увеличением версии")
    void deletesLine() throws Exception {
        insertCart(); insertLine(2);
        Cart cart = read(mvc.perform(delete(cartPath()+"/items/"+stockId).param("expectedCartVersion","0")).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.items()).isEmpty(); assertThat(cart.version()).isEqualTo(1); assertThat(cart.totalAmount()).isEqualTo("0.00");
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isEqualTo(1); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-005. GET неизвестного идентификатора возвращает NOT_FOUND. SQL подтверждает отсутствие новой корзины.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-005: чтение отсутствующей корзины не создаёт строку")
    void rejectsMissingCart() throws Exception {
        mvc.perform(get(cartPath())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE cart_id=?",Integer.class,cartId)).isZero();
    }

    /**
     * STORE-API-DB-006. PUT для неизвестной корзины возвращает NOT_FOUND и не создаёт позицию.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-006: изменение отсутствующей корзины отклоняется без записи позиции")
    void rejectsMissingUpdate() throws Exception {
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(2,0)))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(lineCount()).isZero(); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-007. Корзина существует без позиции. DELETE возвращает NOT_FOUND; версия остаётся нулевой.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-007: удаление отсутствующей позиции сохраняет версию корзины")
    void rejectsMissingDelete() throws Exception {
        insertCart();
        mvc.perform(delete(cartPath()+"/items/"+stockId).param("expectedCartVersion","0")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isZero();
    }

    /**
     * STORE-API-DB-008. PUT с количеством 0 возвращает VALIDATION_ERROR; строки, версия и остаток не меняются.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-008: нулевое количество отклоняется без изменения корзины")
    void rejectsInvalidQuantity() throws Exception {
        insertCart();
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(0,0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isZero(); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-009. SQL фиксирует две единицы и версию 1. PUT с ожидаемой версией 0 возвращает конфликт, сохраняя оба значения.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-009: устаревшая версия отклоняется без изменения позиции")
    void rejectsStaleVersion() throws Exception {
        insertCart(); insertLine(2); jdbc.update("UPDATE carts SET version=1 WHERE cart_id=?",cartId);
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(3,0)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        assertThat(cartVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items WHERE cart_id=?",Integer.class,cartId)).isEqualTo(2);
    }

    /**
     * STORE-API-DB-010. SQL меняет количество с 2 на 3 и версию на 1. GET должен увидеть новую композицию и сумму.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-010: прямое изменение позиции становится видимым при чтении")
    void readsDirectlyUpdatedLine() throws Exception {
        insertCart(); insertLine(2);
        jdbc.update("UPDATE cart_items SET quantity=3 WHERE cart_id=?",cartId); jdbc.update("UPDATE carts SET version=1 WHERE cart_id=?",cartId);
        Cart cart = read(mvc.perform(get(cartPath())).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.version()).isEqualTo(1); assertThat(cart.items()).containsExactly(new CartLine(stockId,"P-1","Мыло",3,"120.00","360.00"));
        assertThat(cart.totalAmount()).isEqualTo("360.00");
    }

    /**
     * STORE-API-DB-011. После удаления позиции через SQL GET возвращает пустую композицию и нулевую сумму.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-011: прямое удаление позиции становится видимым при чтении")
    void readsDirectlyDeletedLine() throws Exception {
        insertCart(); insertLine(2); jdbc.update("DELETE FROM cart_items WHERE cart_id=?",cartId);
        Cart cart = read(mvc.perform(get(cartPath())).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.items()).isEmpty(); assertThat(cart.totalAmount()).isEqualTo("0.00");
    }

    /**
     * STORE-API-DB-012. Поставка и остаток подготовлены SQL. GET каталога возвращает свой товар с количеством 10 и ценой 120.00.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-012: каталог возвращает все поля подготовленного остатка")
    void readsPreparedCatalog() throws Exception {
        Catalog catalog = read(mvc.perform(get("/stores/"+storeId+"/catalog")).andExpect(status().isOk()).andReturn(),Catalog.class);
        assertThat(catalog.storeId()).isEqualTo(storeId);
        assertThat(catalog.items()).containsExactly(new Stock(stockId,"P-1","NON_FOOD","Мыло","Описание","120.00","RUB",10));
    }

    /**
     * STORE-API-DB-013. POST оформления списывает три единицы, закрывает корзину и сохраняет читаемую заявку. Публикация Kafka не запускается.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-013: оформление сохраняет заявку и расход в одной транзакции")
    void acceptsCart() throws Exception {
        insertCart(); insertLine(3);
        var response = mvc.perform(post(cartPath()+"/submit").header("Idempotency-Key","checkout-1").contentType("application/json").content(mapper.writeValueAsString(new SubmitInput(0)))).andExpect(status().isAccepted()).andReturn();
        Submission accepted = read(response,Submission.class);
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT state FROM carts WHERE cart_id=?",String.class,cartId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE store_id=?",Integer.class,storeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_expenses WHERE submission_id=?",Integer.class,accepted.submissionId())).isEqualTo(3);
        assertThat(read(mvc.perform(get("/stores/"+storeId+"/submissions/"+accepted.submissionId())).andExpect(status().isOk()).andReturn(),Submission.class)).isEqualTo(accepted);
    }

    /**
     * STORE-API-DB-014. Повторяем исходные тело и ключ. Проверяем одинаковую заявку, один расход и однократное списание.
     * Действие проходит через настоящий контроллер; состояние проверяется после фиксации транзакции.
     */
    @Test @DisplayName("STORE-API-DB-014: повтор оформления возвращает ту же заявку без нового расхода")
    void replaysAcceptedCart() throws Exception {
        insertCart(); insertLine(3);
        var response = mvc.perform(post(cartPath()+"/submit").header("Idempotency-Key","checkout-1").contentType("application/json").content(mapper.writeValueAsString(new SubmitInput(0)))).andExpect(status().isAccepted()).andReturn();
        Submission accepted = read(response,Submission.class);
        Submission replay = read(mvc.perform(post(cartPath()+"/submit").header("Idempotency-Key","checkout-1").contentType("application/json").content(mapper.writeValueAsString(new SubmitInput(0)))).andExpect(status().isAccepted()).andReturn(),Submission.class);
        assertThat(replay).isEqualTo(accepted);
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT state FROM carts WHERE cart_id=?",String.class,cartId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE store_id=?",Integer.class,storeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_expenses WHERE submission_id=?",Integer.class,accepted.submissionId())).isEqualTo(3);
        assertThat(read(mvc.perform(get("/stores/"+storeId+"/submissions/"+accepted.submissionId())).andExpect(status().isOk()).andReturn(),Submission.class)).isEqualTo(accepted);
    }
    /** Создаёт исходную корзину версии 0 напрямую; композиция добавляется отдельной операцией в конкретном тесте. */
    private void insertCart() {
        jdbc.update("INSERT INTO carts(cart_id,store_id,created_at) VALUES(?,?,?)", cartId, storeId, Timestamp.from(clock.instant()));
    }
    /** Подготавливает одну позицию в своей корзине; количество явно задаётся сценарием. */
    private void insertLine(int quantity) {
        jdbc.update("INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)", storeId, cartId, stockId, quantity);
    }
    /** Возвращает путь своей корзины; маршрут не выбирается скрытыми флагами. */
    private String cartPath() { return "/stores/" + storeId + "/carts/" + cartId; }
    /** Разбирает JSON в production-модель без проверки бизнес-полей; инварианты остаются в тесте. */
    private <T> T read(MvcResult result, Class<T> type) throws Exception { return mapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), type); }
    /** Читает текущую версию корзины отдельным SELECT после завершения изменения. */
    private long cartVersion() { return jdbc.queryForObject("SELECT version FROM carts WHERE cart_id=?", Long.class, cartId); }
    /** Возвращает число позиций своей корзины, включая ноль после отказа или удаления. */
    private int lineCount() { return jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, cartId); }
    /** Читает количество своей складской позиции; добавление в корзину не должно его менять. */
    private int stockQuantity() { return jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE stock_item_id=?", Integer.class, stockId); }

}
