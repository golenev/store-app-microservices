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
 * Проверяет запросы API магазина и сохранённые строки в отдельном PostgreSQL в контейнере. MockMvc
 * выполняет контроллеры и обработку ошибок без сетевого HTTP-сервера. Миграции сервиса создают схему;
 * подготовка данных и операции сервиса завершают свои транзакции до независимого чтения результата. Kafka
 * и базы Docker Compose не используются.
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
    /**
     * Создаёт отдельный магазин, принятую поставку и остаток: 10 единиц товара по 120.00 рублей.
     * Подготавливает данные через SQL без сообщений Kafka.
     */
    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        storeId = "S-" + UUID.randomUUID(); cartId = UUID.randomUUID(); stockId = UUID.randomUUID();
        jdbc.update("INSERT INTO store_scopes VALUES(?)", storeId);
        var now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO stock_receipts(store_id,delivery_id,delivery_sequence,fingerprint,received_at,posted_at,applied_at,payload) VALUES(?, 'D-1',1,?,?,?,?,?)",
                storeId, "a".repeat(64), now, now, now, "{}");
        jdbc.update("INSERT INTO inventory(stock_item_id,store_id,product_id,product_type,short_name,description,unit_price,currency,available_quantity,last_delivery_sequence) VALUES(?,?,'P-1','NON_FOOD','Мыло','Описание',120.00,'RUB',10,1)", stockId, storeId);
    }
    /**
     * Удаляет данные только своего магазина, сначала зависимые строки, затем магазин. Схема и начальные данные
     * миграций сохраняются.
     */
    @AfterEach void cleanup() {
        for (String table : List.of("store_outbox", "stock_expenses", "submissions", "cart_items", "carts", "stock_movements", "inventory", "processed_events", "stock_receipts", "store_scopes"))
            jdbc.update("DELETE FROM " + table + " WHERE store_id=?", storeId);
    }

    /**
     * STORE-API-DB-001. При подготовленном остатке отправляет POST создания корзины. Проверяет, что сохранена
     * одна пустая открытая корзина; её поля совпадают в ответе, SQL и последующем GET.
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
     * STORE-API-DB-002. Через SQL создаёт корзину с двумя единицами товара по 120.00 рублей. Запрашивает её
     * через GET и проверяет поля позиции и сумму 240.00 рублей.
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
     * STORE-API-DB-003. Для корзины версии 0 отправляет PUT с количеством 3. Проверяет ответ и БД: в корзине
     * три единицы, версия стала 1, доступный остаток остался 10.
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
     * STORE-API-DB-004. Для корзины с подготовленной позицией отправляет DELETE. Проверяет пустой состав
     * ответа, удаление строки из БД и сохранение прежнего остатка товара.
     */
    @Test @DisplayName("STORE-API-DB-004: удаление позиции фиксируется с однократным увеличением версии")
    void deletesLine() throws Exception {
        insertCart(); insertLine(2);
        Cart cart = read(mvc.perform(delete(cartPath()+"/items/"+stockId).param("expectedCartVersion","0")).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.items()).isEmpty(); assertThat(cart.version()).isEqualTo(1); assertThat(cart.totalAmount()).isEqualTo("0.00");
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isEqualTo(1); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-005. Запрашивает неизвестную корзину через GET. Проверяет {@code NOT_FOUND} и отсутствие
     * новой строки корзины в БД.
     */
    @Test @DisplayName("STORE-API-DB-005: чтение отсутствующей корзины не создаёт строку")
    void rejectsMissingCart() throws Exception {
        mvc.perform(get(cartPath())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE cart_id=?",Integer.class,cartId)).isZero();
    }

    /**
     * STORE-API-DB-006. Отправляет PUT для неизвестной корзины. Проверяет {@code NOT_FOUND} и отсутствие
     * созданных позиций корзины.
     */
    @Test @DisplayName("STORE-API-DB-006: изменение отсутствующей корзины отклоняется без записи позиции")
    void rejectsMissingUpdate() throws Exception {
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(2,0)))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(lineCount()).isZero(); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-007. Из существующей пустой корзины пытается удалить отсутствующую позицию. Проверяет
     * {@code NOT_FOUND} и сохранение версии 0.
     */
    @Test @DisplayName("STORE-API-DB-007: удаление отсутствующей позиции сохраняет версию корзины")
    void rejectsMissingDelete() throws Exception {
        insertCart();
        mvc.perform(delete(cartPath()+"/items/"+stockId).param("expectedCartVersion","0")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isZero();
    }

    /**
     * STORE-API-DB-008. Отправляет PUT с количеством 0. Проверяет {@code VALIDATION_ERROR}: позиция не
     * создаётся, версия корзины и остаток товара не меняются.
     */
    @Test @DisplayName("STORE-API-DB-008: нулевое количество отклоняется без изменения корзины")
    void rejectsInvalidQuantity() throws Exception {
        insertCart();
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(0,0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(lineCount()).isZero(); assertThat(cartVersion()).isZero(); assertThat(stockQuantity()).isEqualTo(10);
    }

    /**
     * STORE-API-DB-009. Через SQL задаёт две единицы товара и версию корзины 1. Отправляет PUT с ожидаемой
     * версией 0 и проверяет конфликт; количество и версия остаются прежними.
     */
    @Test @DisplayName("STORE-API-DB-009: устаревшая версия отклоняется без изменения позиции")
    void rejectsStaleVersion() throws Exception {
        insertCart(); insertLine(2); jdbc.update("UPDATE carts SET version=1 WHERE cart_id=?",cartId);
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(3,0)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        assertThat(cartVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items WHERE cart_id=?",Integer.class,cartId)).isEqualTo(2);
    }

    /**
     * STORE-API-DB-010. Через SQL меняет количество товара в корзине с 2 на 3 и устанавливает версию 1. GET
     * должен вернуть новый состав и соответствующую сумму.
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
     * STORE-API-DB-011. Удаляет позицию корзины через SQL. GET должен вернуть пустой список позиций и нулевую
     * сумму.
     */
    @Test @DisplayName("STORE-API-DB-011: прямое удаление позиции становится видимым при чтении")
    void readsDirectlyDeletedLine() throws Exception {
        insertCart(); insertLine(2); jdbc.update("DELETE FROM cart_items WHERE cart_id=?",cartId);
        Cart cart = read(mvc.perform(get(cartPath())).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.items()).isEmpty(); assertThat(cart.totalAmount()).isEqualTo("0.00");
    }

    /**
     * STORE-API-DB-012. При подготовленных поставке и остатке запрашивает каталог. Проверяет свой товар:
     * доступно 10 единиц, цена 120.00 рублей.
     */
    @Test @DisplayName("STORE-API-DB-012: каталог возвращает все поля подготовленного остатка")
    void readsPreparedCatalog() throws Exception {
        Catalog catalog = read(mvc.perform(get("/stores/"+storeId+"/catalog")).andExpect(status().isOk()).andReturn(),Catalog.class);
        assertThat(catalog.storeId()).isEqualTo(storeId);
        assertThat(catalog.items()).containsExactly(new Stock(stockId,"P-1","NON_FOOD","Мыло","Описание","120.00","RUB",10));
    }

    /**
     * STORE-API-DB-013. Оформляет корзину с тремя единицами через POST. Проверяет списание товара, закрытие
     * корзины и сохранение заявки, доступной для чтения. Kafka в этом тесте не отправляет событие.
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
     * STORE-API-DB-014. Повторяет оформление с теми же телом и ключом. Проверяет совпадение принятой заявки,
     * одно движение расхода и однократное уменьшение остатка.
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
    /**
     * Сохраняет через SQL пустую корзину версии 0. Позиции добавляет сам сценарий отдельным вызовом.
     */
    private void insertCart() {
        jdbc.update("INSERT INTO carts(cart_id,store_id,created_at) VALUES(?,?,?)", cartId, storeId, Timestamp.from(clock.instant()));
    }
    /**
     * Сохраняет через SQL одну позицию своей корзины с указанным количеством.
     *
     * @param quantity количество единиц товара
     */
    private void insertLine(int quantity) {
        jdbc.update("INSERT INTO cart_items(store_id,cart_id,stock_item_id,quantity) VALUES(?,?,?,?)", storeId, cartId, stockId, quantity);
    }
    /**
     * Возвращает адрес API для собственной корзины теста.
     *
     * @return адрес API собственной корзины
     */
    private String cartPath() { return "/stores/" + storeId + "/carts/" + cartId; }
    /**
     * Читает JSON-ответ в модель указанного типа. Проверки полей и бизнес-ожиданий остаются в сценарии.
     *
     * @param <T> тип модели, которую нужно прочитать из JSON
     * @param result результат обработки запроса MockMvc с JSON-телом ответа
     * @param type Java-класс модели, в которую нужно прочитать JSON
     * @return объект указанного типа, прочитанный из JSON
     */
    private <T> T read(MvcResult result, Class<T> type) throws Exception { return mapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), type); }
    /**
     * Читает версию корзины отдельным SQL-запросом после завершения операции сервиса.
     *
     * @return текущая версия собственной корзины
     */
    private long cartVersion() { return jdbc.queryForObject("SELECT version FROM carts WHERE cart_id=?", Long.class, cartId); }
    /**
     * Считает строки позиций своей корзины в БД. Ноль позволяет проверить отсутствие записи после отказа или
     * удаления.
     *
     * @return число позиций собственной корзины
     */
    private int lineCount() { return jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, cartId); }
    /**
     * Читает доступное количество своего товара из БД для проверки, что операция с корзиной не списала
     * остаток.
     *
     * @return текущее доступное количество своего товара
     */
    private int stockQuantity() { return jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE stock_item_id=?", Integer.class, stockId); }

}
