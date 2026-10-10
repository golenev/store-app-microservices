package com.tariffs.pyramid.api_database;

import com.tariffs.dto.RuleRequest;
import com.tariffs.dto.RulesResponse;
import com.tariffs.model.Rule;

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
 * Проверяет создание, чтение, замену и удаление правил через контроллеры TARIFFS и отдельный PostgreSQL в
 * контейнере. MockMvc обрабатывает запросы без сетевого HTTP-сервера; схема создаётся миграциями сервиса.
 * Подготовка SQL сохраняется до запроса, результат читается после завершения транзакции сервиса. Расчёт
 * наценки и Redis не проверяются.
 */
@Tag("api-database") @Testcontainers @WebAppConfiguration @SpringJUnitConfig(TariffApiDatabaseConfig.class)
class TariffApiDatabaseTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;
    private String cityId;

    /**
     * Назначает отдельный город для данных теста и собирает MockMvc на Spring-контексте с контроллерами,
     * проверкой полей и обработкой ошибок.
     */
    @BeforeEach void prepare() { cityId="T-"+UUID.randomUUID(); mvc=MockMvcBuilders.webAppContextSetup(context).build(); }
    /**
     * Удаляет только правила города текущего теста. Начальные данные миграций сохраняются.
     */
    @AfterEach void cleanup() { jdbc.update("DELETE FROM tariff_rules WHERE city_id=?",cityId); }

    /**
     * TAR-CRUD-001-API-DB. Отправляет POST с допустимыми условиями. Проверяет все поля созданного правила, его
     * адрес в {@code Location} и сохранённые колонки независимым SQL-запросом.
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
     * TAR-CRUD-002-API-DB. Через SQL сохраняет все восемь колонок правила. GET должен вернуть те же условия и
     * версию.
     */
    @Test @DisplayName("TAR-CRUD-002-API-DB: подготовленная строка читается через контроллер")
    void readsRule() throws Exception {
        UUID id=insertRule();
        Rule actual=read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class);
        assertThat(actual).isEqualTo(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /**
     * TAR-CRUD-003-API-DB. Через PUT заменяет подготовленное правило, меняя тип товара, границы и наценку.
     * Проверяет ответ и сохранённые колонки; верхняя граница должна стать {@code null}.
     */
    @Test @DisplayName("TAR-CRUD-003-API-DB: замена фиксирует новые условия и версию 2")
    void replacesRule() throws Exception {
        UUID id=insertRule(); RuleRequest replacement=new RuleRequest("FOOD",cityId,"RUB","5.00",null,"0.30");
        Rule actual=read(mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(replacement))).andExpect(status().isOk()).andReturn(),Rule.class);
        assertThat(actual).isEqualTo(new Rule(id,2,"FOOD",cityId,"RUB","5.00",null,"0.30"));
        assertThat(stored(id)).containsExactly(new PersistedTariffRule(id,2,"FOOD",cityId,"RUB",new BigDecimal("5.00"),null,new BigDecimal("0.300000")));
    }

    /**
     * TAR-CRUD-004-API-DB. Удаляет подготовленное правило через DELETE. Проверяет отсутствие строки
     * независимым SQL-запросом и ошибку последующего GET.
     */
    @Test @DisplayName("TAR-CRUD-004-API-DB: удаление убирает строку и делает правило недоступным")
    void deletesRule() throws Exception {
        UUID id=insertRule();
        mvc.perform(delete("/tariffs/rules/"+id)).andExpect(status().isNoContent()); assertThat(stored(id)).isEmpty();
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * TAR-CRUD-005-API-DB. Для отсутствующего UUID отправляет GET. Проверяет {@code NOT_FOUND}; до и после
     * запроса строки с этим UUID нет.
     */
    @Test @DisplayName("TAR-CRUD-005-API-DB: чтение отсутствующего правила отклоняется без создания строки")
    void rejectsMissingRead() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-006-API-DB. Для отсутствующего UUID отправляет допустимую замену через PUT. Проверяет {@code
     * NOT_FOUND} и отсутствие новой строки после отказа.
     */
    @Test @DisplayName("TAR-CRUD-006-API-DB: замена отсутствующего правила отклоняется без создания строки")
    void rejectsMissingUpdate() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        RuleRequest replacement=new RuleRequest("FOOD",cityId,"RUB","5.00",null,"0.30");
        mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(replacement))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-007-API-DB. Для отсутствующего UUID отправляет DELETE. Проверяет {@code NOT_FOUND}; строка с
     * этим UUID не появляется.
     */
    @Test @DisplayName("TAR-CRUD-007-API-DB: удаление отсутствующего правила отклоняется без создания строки")
    void rejectsMissingDelete() throws Exception {
        UUID id=UUID.randomUUID(); assertThat(stored(id)).isEmpty();
        mvc.perform(delete("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(stored(id)).isEmpty();
    }

    /**
     * TAR-CRUD-008-API-DB. Отправляет POST с одинаковыми границами 100.00. Проверяет {@code VALIDATION_ERROR}
     * и отсутствие правил своего города в БД.
     */
    @Test @DisplayName("TAR-CRUD-008-API-DB: пустой диапазон не создаёт правило")
    void rejectsInvalidCreate() throws Exception {
        RuleRequest invalid=new RuleRequest("NON_FOOD",cityId,"RUB","100.00","100.00","0.20");
        mvc.perform(post("/tariffs/rules").contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_rules WHERE city_id=?",Integer.class,cityId)).isZero();
    }

    /**
     * TAR-CRUD-009-API-DB. Пытается заменить существующее правило через PUT с равными границами. Проверяет
     * {@code VALIDATION_ERROR}; SQL и GET должны вернуть все прежние поля и версию.
     */
    @Test @DisplayName("TAR-CRUD-009-API-DB: отказ замены сохраняет все исходные колонки")
    void rejectsInvalidUpdate() throws Exception {
        UUID id=insertRule(); RuleRequest invalid=new RuleRequest("NON_FOOD",cityId,"RUB","100.00","100.00","0.20");
        mvc.perform(put("/tariffs/rules/"+id).contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stored(id)).containsExactly(new PersistedTariffRule(id,1,"NON_FOOD",cityId,"RUB",new BigDecimal("0.00"),new BigDecimal("500.00"),new BigDecimal("0.200000")));
        assertThat(read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class)).isEqualTo(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /**
     * TAR-CRUD-010-API-DB. Через SQL меняет поля правила и устанавливает версию 2. GET должен вернуть именно
     * новые значения.
     */
    @Test @DisplayName("TAR-CRUD-010-API-DB: прямые изменения SQL становятся видимыми через GET")
    void readsDirectUpdate() throws Exception {
        UUID id=insertRule();
        jdbc.update("UPDATE tariff_rules SET version=2,product_type='FOOD',lower_bound=5.00,upper_bound=NULL,markup_rate=0.30 WHERE tariff_rule_id=?",id);
        assertThat(read(mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isOk()).andReturn(),Rule.class)).isEqualTo(new Rule(id,2,"FOOD",cityId,"RUB","5.00",null,"0.30"));
    }

    /**
     * TAR-CRUD-011-API-DB. Удаляет подготовленное правило напрямую через SQL. GET должен вернуть {@code
     * NOT_FOUND}.
     */
    @Test @DisplayName("TAR-CRUD-011-API-DB: прямое удаление SQL становится видимым через GET")
    void readsDirectDelete() throws Exception {
        UUID id=insertRule(); jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?",id);
        mvc.perform(get("/tariffs/rules/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * TAR-CRUD-012-API-DB. После подготовки правила через SQL запрашивает общий список. Отбирает правила
     * своего города и проверяет одну запись со всеми исходными условиями.
     */
    @Test @DisplayName("TAR-CRUD-012-API-DB: список содержит все поля подготовленного правила")
    void listsPreparedRule() throws Exception {
        UUID id=insertRule();
        RulesResponse response=read(mvc.perform(get("/tariffs/rules")).andExpect(status().isOk()).andReturn(),RulesResponse.class);
        assertThat(response.items().stream().filter(rule -> rule.cityId().equals(cityId)).toList()).containsExactly(new Rule(id,1,"NON_FOOD",cityId,"RUB","0.00","500.00","0.20"));
    }

    /**
     * Возвращает допустимые исходные условия правила для своего города, не записывая их в БД.
     *
     * @return допустимые исходные условия правила
     */
    private RuleRequest input() { return new RuleRequest("NON_FOOD",cityId,"RUB","0.00","500.00","0.20"); }
    /**
     * Сохраняет через SQL правило версии 1 с новым UUID и возвращает этот идентификатор.
     *
     * @return UUID подготовленного тарифного правила
     */
    private UUID insertRule() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,upper_bound,markup_rate) VALUES(?,1,'NON_FOOD',?,'RUB',0.00,500.00,0.20)",id,cityId);
        return id;
    }
    /**
     * Читает все колонки правила напрямую из БД без репозитория сервиса. Пустой список означает отсутствие
     * строки.
     *
     * @param id UUID тарифного правила
     * @return строки правила из БД или пустой список, если UUID отсутствует
     */
    private List<PersistedTariffRule> stored(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=?",(row,number) -> new PersistedTariffRule(
                row.getObject("tariff_rule_id",UUID.class),row.getLong("version"),row.getString("product_type"),row.getString("city_id"),
                row.getString("currency"),row.getBigDecimal("lower_bound"),row.getBigDecimal("upper_bound"),row.getBigDecimal("markup_rate")),id);
    }
    /**
     * Читает JSON-ответ в модель указанного типа. Проверки значений выполняет сам сценарий.
     *
     * @param <T> тип модели, которую нужно прочитать из JSON
     * @param result результат обработки запроса MockMvc с JSON-телом ответа
     * @param type Java-класс модели, в которую нужно прочитать JSON
     * @return объект указанного типа, прочитанный из JSON
     */
    private <T> T read(MvcResult result,Class<T> type) throws Exception { return mapper.readValue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),type); }
}
