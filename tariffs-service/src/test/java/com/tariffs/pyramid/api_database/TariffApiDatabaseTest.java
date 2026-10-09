package com.tariffs.pyramid.api_database;

import com.tariffs.api.TariffModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Те же CRUD-сценарии через настоящие контроллеры TARIFFS и отдельную PostgreSQL Testcontainers.
 * MockMvc выполняет обработку HTTP без TCP; production Flyway и транзакционный сервис работают с настоящими строками.
 * SQL фиксируется до запроса, независимое чтение выполняется после commit. Котировки и Redis в набор не включены.
 */
@Tag("api-database") @Testcontainers @WebAppConfiguration @SpringJUnitConfig(TariffApiDatabaseConfig.class)
class TariffApiDatabaseTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;
    private String cityId;

    /** Выбирает свой город и собирает MVC на настоящем контексте, а не на standalone-контроллере. */
    @BeforeEach void prepare() { cityId="T-"+UUID.randomUUID(); mvc=MockMvcBuilders.webAppContextSetup(context).build(); }
    /** Удаляет только правила своего города, сохраняя миграционные fixtures. */
    @AfterEach void cleanup() { jdbc.update("DELETE FROM tariff_rules WHERE city_id=?",cityId); }

    /**
     * TAR-CRUD-001-API-DB. Подготовленные условия передаём POST. Проверяем полный ответ, Location и независимый снимок SQL.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-001-API-DB: создание сохраняет все колонки версии 1")
    void createsRule() throws Exception {
        RuleRequest request=input();
        var response=mvc.perform(post("/tariffs/rules").contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isCreated()).andReturn();
        Rule actual=read(response,Rule.class); UUID id=actual.tariffRuleId();
        assertThat(actual).isEqualTo(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
        assertThat(response.getResponse().getHeader("Location")).isEqualTo("/tariffs/rules/"+id);
        assertThat(stored(id)).containsExactly(new PersistedTariffRule(id,1,"NON_FOOD",cityId,"RUB",new BigDecimal("0.00"),new BigDecimal("500.00"),new BigDecimal("0.200000")));
    }

    /**
     * TAR-CRUD-002-API-DB. SQL фиксирует восемь колонок. GET должен вернуть все исходные условия и версию.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-002-API-DB: подготовленная строка читается через контроллер")
    void readsRule() throws Exception {
        UUID id=insertRule();
        Rule actual=read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class);
        assertThat(actual).isEqualTo(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /**
     * TAR-CRUD-003-API-DB. Для SQL-подготовленной строки PUT меняет тип, границы и наценку; независимый SQL подтверждает null верхнего предела.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-003-API-DB: замена фиксирует новые условия и версию 2")
    void replacesRule() throws Exception {
        UUID id=insertRule(); RuleRequest replacement=new RuleRequest("FOOD",cityId,"RUB","5.00",null,"0.30");
        Rule actual=read(mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(replacement))).andExpect(status().isOk()).andReturn(),Rule.class);
        assertThat(actual).isEqualTo(new Rule(id,2,"FOOD",cityId,"RUB","5.00",null,"0.30"));
        assertThat(stored(id)).containsExactly(new PersistedTariffRule(id,2,"FOOD",cityId,"RUB",new BigDecimal("5.00"),null,new BigDecimal("0.300000")));
    }

    /**
     * TAR-CRUD-004-API-DB. DELETE подготовленной строки проверяется независимым SQL и последующим GET.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-004-API-DB: удаление убирает строку и делает правило недоступным")
    void deletesRule() throws Exception {
        UUID id=insertRule();
        mvc.perform(delete("/tariffs/rules/"+id)).andExpect(status().isNoContent()); assertThat(stored(id)).isEmpty();
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * TAR-CRUD-005-API-DB. Подтверждаем отсутствие до запроса и после NOT_FOUND; замена не должна создавать запись.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-005-API-DB: чтение отсутствующего правила отклоняется без создания строки")
    void rejectsMissingRead() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-006-API-DB. Подтверждаем отсутствие до запроса и после NOT_FOUND; замена не должна создавать запись.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-006-API-DB: замена отсутствующего правила отклоняется без создания строки")
    void rejectsMissingUpdate() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        RuleRequest replacement=new RuleRequest("FOOD",cityId,"RUB","5.00",null,"0.30");
        mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(replacement))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-007-API-DB. Подтверждаем отсутствие до запроса и после NOT_FOUND; замена не должна создавать запись.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-007-API-DB: удаление отсутствующего правила отклоняется без создания строки")
    void rejectsMissingDelete() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        mvc.perform(delete("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-008-API-DB. Равные границы 100.00 образуют недопустимый диапазон. POST возвращает ошибку и не сохраняет строк.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-008-API-DB: пустой диапазон не создаёт правило")
    void rejectsInvalidCreate() throws Exception {
        RuleRequest invalid=new RuleRequest("NON_FOOD",cityId,"RUB","100.00","100.00","0.20");
        mvc.perform(post("/tariffs/rules").contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id=?",Integer.class,cityId)).isZero();
    }

    /**
     * TAR-CRUD-009-API-DB. После PUT с равными границами SQL-снимок и GET должны остаться прежними.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-009-API-DB: отказ замены сохраняет все исходные колонки")
    void rejectsInvalidUpdate() throws Exception {
        UUID id=insertRule(); RuleRequest invalid=new RuleRequest("NON_FOOD",cityId,"RUB","100.00","100.00","0.20");
        mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stored(id)).containsExactly(new PersistedTariffRule(id,1,"NON_FOOD",cityId,"RUB",new BigDecimal("0.00"),new BigDecimal("500.00"),new BigDecimal("0.200000")));
        assertThat(read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class)).isEqualTo(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /**
     * TAR-CRUD-010-API-DB. SQL фиксирует новые поля и версию 2. Контроллер должен прочитать именно их.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-010-API-DB: прямые изменения SQL становятся видимыми через GET")
    void readsDirectUpdate() throws Exception {
        UUID id=insertRule();
        jdbc.update("UPDATE tariff_rules SET version=2,product_type='FOOD',lower_bound=5.00,upper_bound=NULL,markup_rate=0.30 WHERE tariff_rule_id=?",id);
        assertThat(read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class)).isEqualTo(new Rule(id,2,"FOOD",cityId,"RUB","5.00",null,"0.30"));
    }

    /**
     * TAR-CRUD-011-API-DB. Удаляем свою строку непосредственно в БД; GET возвращает NOT_FOUND.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-011-API-DB: прямое удаление SQL становится видимым через GET")
    void readsDirectDelete() throws Exception {
        UUID id=insertRule(); jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?",id);
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * TAR-CRUD-012-API-DB. После INSERT общий список содержит ровно своё правило с исходными условиями; чужие fixtures не учитываются.
     * Запрос выполняется после фиксации подготовки, проверка SQL — после завершения HTTP-транзакции.
     */
    @Test @DisplayName("TAR-CRUD-012-API-DB: список содержит все поля подготовленного правила")
    void listsPreparedRule() throws Exception {
        UUID id=insertRule();
        RulesResponse response=read(mvc.perform(get("/tariffs/rules")).andExpect(status().isOk()).andReturn(),RulesResponse.class);
        assertThat(response.items().stream().filter(rule -> rule.cityId().equals(cityId)).toList()).containsExactly(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /** Подготавливает исходные допустимые условия без записи в БД. */
    private RuleRequest input() { return new RuleRequest("NON_FOOD",cityId,"RUB","0.00","500.00","0.20"); }
    /** Фиксирует отдельную строку версии 1 непосредственно в SQL и возвращает собственный идентификатор. */
    private UUID insertRule() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,upper_bound,markup_rate) VALUES(?,1,'NON_FOOD',?,'RUB',0.00,500.00,0.20)",id,cityId);
        return id;
    }
    /** Читает полный снимок всех колонок без использования production-репозитория; отсутствие возвращает пустой список. */
    private List<PersistedTariffRule> stored(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=?",(row,number) -> new PersistedTariffRule(
                row.getObject("tariff_rule_id",UUID.class),row.getLong("version"),row.getString("product_type"),row.getString("city_id"),
                row.getString("currency"),row.getBigDecimal("lower_bound"),row.getBigDecimal("upper_bound"),row.getBigDecimal("markup_rate")),id);
    }
    /** Разбирает HTTP-ответ в типизированную модель, оставляя проверки внутри сценария. */
    private <T> T read(MvcResult result,Class<T> type) throws Exception { return mapper.readValue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),type); }
}
