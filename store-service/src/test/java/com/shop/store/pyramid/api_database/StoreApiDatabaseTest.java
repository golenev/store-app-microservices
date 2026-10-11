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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.shop.store.entity.*;
import com.shop.store.repository.jpa.*;
import com.shop.store.service.GoodsReceiptService;
import com.shop.store.service.SubmissionService;
import com.shop.store.service.SubmissionTransactionService;
import com.shop.store.codec.ShopCodec;
import com.shop.store.model.OutboxWork;
import com.shop.store.repository.SubmissionRepository;
import com.shop.store.entity.IncomingDiagnosticId;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.messaging.dto.PostedPayload;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.util.function.Supplier;
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
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет запросы API магазина и сохранённые строки в отдельном PostgreSQL в контейнере. MockMvc
 * выполняет контроллеры и обработку ошибок без сетевого HTTP-сервера. Flyway создаёт схему, Hibernate проверяет её;
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
    @PersistenceContext private EntityManager entities;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private StoreScopeJpaRepository stores;
    @Autowired private ReceiptJpaRepository receipts;
    @Autowired private InventoryJpaRepository stocks;
    @Autowired private CartJpaRepository carts;
    @Autowired private CartItemJpaRepository items;
    @Autowired private GoodsReceiptService goods;
    @Autowired private SubmissionService submissions;
    @Autowired private SubmissionTransactionService submissionTransactions;
    @Autowired private ShopCodec codec;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private OutboxJpaRepository outbox;
    @Autowired private ObjectMapper mapper;
    @Autowired private Clock clock;
    private MockMvc mvc;
    private String storeId;
    private UUID cartId;
    private UUID stockId;
    /**
     * Создаёт отдельный магазин, принятую поставку и остаток: 10 единиц товара по 120.00 рублей.
     * Подготавливает данные через JPA без сообщений Kafka.
     */
    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        storeId = "S-" + UUID.randomUUID(); cartId = UUID.randomUUID(); stockId = UUID.randomUUID();
        writeDb(() -> {
            stores.saveAndFlush(StoreScopeEntity.builder().storeId(storeId).build());
            receipts.saveAndFlush(ReceiptEntity.builder().storeId(storeId).deliveryId("D-1").deliverySequence(1)
                    .fingerprint("a".repeat(64)).receivedAt(clock.instant()).postedAt(clock.instant())
                    .appliedAt(clock.instant()).payload("{}").build());
            stocks.saveAndFlush(InventoryEntity.builder().stockItemId(stockId).storeId(storeId).productId("P-1")
                    .productType("NON_FOOD").shortName("Мыло").description("Описание").unitPrice(new BigDecimal("120.00"))
                    .currency("RUB").availableQuantity(10).lastDeliverySequence(1).build());
        });
    }
    /**
     * Удаляет данные только своего магазина, сначала зависимые строки, затем магазин. Схема и начальные данные
     * миграций сохраняются.
     */
    @AfterEach void cleanup() {
        writeDb(() -> {
            entities.createQuery("delete from IncomingDiagnosticEntity d where d.topic in :topics")
                    .setParameter("topics", List.of("invalid-" + storeId, "goods-" + storeId)).executeUpdate();
            for (String entity : List.of("OutboxEntity", "StockExpenseEntity", "SubmissionEntity", "CartItemEntity",
                    "CartEntity", "StockMovementEntity", "InventoryEntity", "ProcessedEventEntity", "ReceiptEntity", "StoreScopeEntity")) {
                entities.createQuery("delete from " + entity + " e where e.storeId = :store")
                        .setParameter("store", storeId).executeUpdate();
            }
        });
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
        assertThat(readDb(() -> carts.findByStoreIdAndCartId(storeId, created.cartId()).orElseThrow().getVersion())).isZero();
        assertThat(readDb(() -> carts.findByStoreIdAndCartId(storeId, created.cartId()).orElseThrow().getState())).isEqualTo("OPEN");
        assertThat(read(mvc.perform(get("/stores/"+storeId+"/carts/"+created.cartId())).andExpect(status().isOk()).andReturn(), Cart.class)).isEqualTo(created);
    }

    /**
     * STORE-API-DB-002. Через JPA создаёт корзину с двумя единицами товара по 120.00 рублей. Запрашивает её
     * через GET и проверяет поля позиции и сумму 240.00 рублей.
     */
    @Test @DisplayName("STORE-API-DB-002: корзина из JPA читается с позицией и точной суммой")
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
        assertThat(readDb(() -> items.findById(new CartItemId(cartId, stockId)).orElseThrow().getQuantity())).isEqualTo(3);
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
        assertThat(readDb(() -> carts.existsById(cartId) ? 1 : 0)).isZero();
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
     * STORE-API-DB-009. Через JPA задаёт две единицы товара и версию корзины 1. Отправляет PUT с ожидаемой
     * версией 0 и проверяет конфликт; количество и версия остаются прежними.
     */
    @Test @DisplayName("STORE-API-DB-009: устаревшая версия отклоняется без изменения позиции")
    void rejectsStaleVersion() throws Exception {
        insertCart(); insertLine(2); writeDb(() -> carts.findById(cartId).orElseThrow().setVersion(1));
        mvc.perform(put(cartPath()+"/items/"+stockId).contentType("application/json").content(mapper.writeValueAsString(new PutItem(3,0)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        assertThat(cartVersion()).isEqualTo(1);
        assertThat(readDb(() -> items.findById(new CartItemId(cartId, stockId)).orElseThrow().getQuantity())).isEqualTo(2);
    }

    /**
     * STORE-API-DB-010. Через JPA меняет количество товара в корзине с 2 на 3 и устанавливает версию 1. GET
     * должен вернуть новый состав и соответствующую сумму.
     */
    @Test @DisplayName("STORE-API-DB-010: прямое изменение позиции становится видимым при чтении")
    void readsDirectlyUpdatedLine() throws Exception {
        insertCart(); insertLine(2);
        writeDb(() -> items.findById(new CartItemId(cartId, stockId)).orElseThrow().setQuantity(3)); writeDb(() -> carts.findById(cartId).orElseThrow().setVersion(1));
        Cart cart = read(mvc.perform(get(cartPath())).andExpect(status().isOk()).andReturn(),Cart.class);
        assertThat(cart.version()).isEqualTo(1); assertThat(cart.items()).containsExactly(new CartLine(stockId,"P-1","Мыло",3,"120.00","360.00"));
        assertThat(cart.totalAmount()).isEqualTo("360.00");
    }

    /**
     * STORE-API-DB-011. Удаляет позицию корзины через JPA. GET должен вернуть пустой список позиций и нулевую
     * сумму.
     */
    @Test @DisplayName("STORE-API-DB-011: прямое удаление позиции становится видимым при чтении")
    void readsDirectlyDeletedLine() throws Exception {
        insertCart(); insertLine(2); writeDb(() -> items.deleteById(new CartItemId(cartId, stockId)));
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
        assertThat(readDb(() -> carts.findById(cartId).orElseThrow().getState())).isEqualTo("SUBMITTED");
        assertThat(readDb(() -> entities.createQuery("select count(s) from SubmissionEntity s where s.storeId = :store", Long.class).setParameter("store", storeId).getSingleResult())).isEqualTo(1);
        assertThat(readDb(() -> entities.find(StockExpenseEntity.class, new StockExpenseId(accepted.submissionId(), stockId)).getQuantity())).isEqualTo(3);
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
        assertThat(readDb(() -> carts.findById(cartId).orElseThrow().getState())).isEqualTo("SUBMITTED");
        assertThat(readDb(() -> entities.createQuery("select count(s) from SubmissionEntity s where s.storeId = :store", Long.class).setParameter("store", storeId).getSingleResult())).isEqualTo(1);
        assertThat(readDb(() -> entities.find(StockExpenseEntity.class, new StockExpenseId(accepted.submissionId(), stockId)).getQuantity())).isEqualTo(3);
        assertThat(read(mvc.perform(get("/stores/"+storeId+"/submissions/"+accepted.submissionId())).andExpect(status().isOk()).andReturn(),Submission.class)).isEqualTo(accepted);
    }
    /**
     * Сохраняет через JPA пустую корзину версии 0. Позиции добавляет сам сценарий отдельным вызовом.
     */
    private void insertCart() {
        writeDb(() -> carts.saveAndFlush(CartEntity.builder().cartId(cartId).storeId(storeId).version(0).state("OPEN").createdAt(clock.instant()).build()));
    }
    /**
     * Сохраняет через JPA одну позицию своей корзины с указанным количеством.
     *
     * @param quantity количество единиц товара
     */
    private void insertLine(int quantity) {
        writeDb(() -> items.saveAndFlush(CartItemEntity.builder().storeId(storeId).cartId(cartId).stockItemId(stockId).quantity(quantity).build()));
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
     * Читает версию корзины в отдельной транзакции JPA после завершения операции сервиса.
     *
     * @return текущая версия собственной корзины
     */
    private long cartVersion() { return readDb(() -> carts.findById(cartId).orElseThrow().getVersion()); }
    /**
     * Считает строки позиций своей корзины в БД. Ноль позволяет проверить отсутствие записи после отказа или
     * удаления.
     *
     * @return число позиций собственной корзины
     */
    private int lineCount() { return readDb(() -> Math.toIntExact(entities.createQuery("select count(c) from CartItemEntity c where c.cartId = :cart", Long.class).setParameter("cart", cartId).getSingleResult())); }
    /**
     * Читает доступное количество своего товара из БД для проверки, что операция с корзиной не списала
     * остаток.
     *
     * @return текущее доступное количество своего товара
     */
    private int stockQuantity() { return readDb(() -> stocks.findById(stockId).orElseThrow().getAvailableQuantity()); }

    /**
     * STORE-JPA-001. Получает новую дорогую поставку раньше старой; повторяет событие и ту же поставку
     * с новым eventId. Проверяет два прихода, один остаток и сохранение более новой цены.
     */
    @Test
    void appliesDeliveriesOnceWithoutRevertingPrice() {
        GoodsEvent newer = delivery("D-3", 3, "200.00", "240.00", 2);
        GoodsEvent older = delivery("D-2", 2, "100.00", "120.00", 3);
        String topic = "goods-" + storeId;
        goods.receive(topic, 0, 1, storeId, codec.json(newer));
        goods.receive(topic, 0, 2, storeId, codec.json(older));
        goods.receive(topic, 0, 3, storeId, codec.json(newer));
        GoodsEvent replay = new GoodsEvent(UUID.randomUUID(), newer.eventType(), newer.schemaVersion(),
                newer.occurredAt(), newer.storeId(), newer.payload());
        goods.receive(topic, 0, 4, storeId, codec.json(replay));
        assertThat(stockQuantity()).isEqualTo(15);
        assertThat(readDb(() -> stocks.findById(stockId).orElseThrow().getUnitPrice())).isEqualByComparingTo("240.00");
        assertThat(readDb(() -> stocks.findById(stockId).orElseThrow().getLastDeliverySequence())).isEqualTo(3);
        assertThat(countRows("ReceiptEntity")).isEqualTo(3);
        assertThat(countRows("StockMovementEntity")).isEqualTo(2);
        assertThat(countRows("ProcessedEventEntity")).isEqualTo(3);
    }

    /** STORE-JPA-011. Принимает новый продукт: persist/flush сохраняют приход до остатка и движения с внешними ключами. */
    @Test
    void createsInventoryForNewProduct() {
        GoodsEvent input = delivery("D-new", 2, "50.00", "60.00", 4);
        String raw = codec.json(input).replace("P-1", "P-new");
        goods.receive("goods-" + storeId, 0, 9, storeId, raw);
        var inventory = readDb(() -> stocks.findByStoreIdOrderByProductId(storeId,
                org.springframework.data.domain.PageRequest.of(0, 1001)).stream()
                .filter(entity -> entity.getProductId().equals("P-new"))
                .map(entity -> new Stock(entity.getStockItemId(), entity.getProductId(), entity.getProductType(),
                        entity.getShortName(), entity.getDescription(), entity.getUnitPrice().toPlainString(),
                        entity.getCurrency(), entity.getAvailableQuantity())).toList());
        assertThat(inventory).hasSize(1);
        assertThat(inventory.getFirst().availableQuantity()).isEqualTo(4);
        assertThat(inventory.getFirst().unitPrice()).isEqualTo("60.00");
        assertThat(stockQuantity()).isEqualTo(10);
        assertThat(countRows("ReceiptEntity")).isEqualTo(2);
        assertThat(countRows("StockMovementEntity")).isEqualTo(1);
        assertThat(countRows("ProcessedEventEntity")).isEqualTo(1);
    }

    /** STORE-JPA-002. Дважды принимает неверный JSON с теми же координатами; диагностика сохраняется один раз. */
    @Test
    void deduplicatesDiagnosticCoordinates() {
        String topic = "invalid-" + storeId;
        goods.receive(topic, 0, 7, storeId, "{}");
        goods.receive(topic, 0, 7, storeId, "{}");
        assertThat(readDb(() -> entities.find(IncomingDiagnosticEntity.class,
                new IncomingDiagnosticId(topic, 0, 7L)).getCode())).isEqualTo("VALIDATION_ERROR");
        assertThat(readDb(() -> entities.createQuery("select count(d) from IncomingDiagnosticEntity d where d.topic = :topic", Long.class)
                .setParameter("topic", topic).getSingleResult())).isEqualTo(1);
        assertThat(stockQuantity()).isEqualTo(10);
    }

    /** STORE-JPA-003. Оформляет 11 единиц при остатке 10; отказ не создаёт принятие, расход или outbox. */
    @Test
    void insufficientStockLeavesNoAcceptanceWrites() {
        insertCart();
        insertLine(11);
        assertThatThrownBy(() -> submissions.submit(storeId, cartId, "not-enough", new SubmitInput(0)))
                .isInstanceOfSatisfying(ShopException.class, error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(stockQuantity()).isEqualTo(10);
        assertThat(cartVersion()).isZero();
        assertThat(countRows("SubmissionEntity")).isZero();
        assertThat(countRows("StockExpenseEntity")).isZero();
        assertThat(countRows("OutboxEntity")).isZero();
    }

    /**
     * STORE-JPA-004. Сохраняет принятие, расход, outbox и закрытие, затем нарушает UNIQUE расхода после flush/clear.
     * Ошибка БД откатывает уже отправленные SQL и изменения управляемых сущностей одной транзакции.
     */
    @Test
    void flushFailureRollsBackEveryAcceptanceWrite() {
        insertCart();
        UUID submission = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        assertThatThrownBy(() -> writeDb(() -> {
            submissionRepository.insertSubmission(submission, storeId, cartId, "rollback", "a".repeat(64), 0, event, now, "{}");
            submissionRepository.deductStock(3, storeId, stockId);
            submissionRepository.insertExpense(storeId, submission, stockId, 3, new BigDecimal("120.00"), now);
            submissionRepository.insertOutbox(event, submission, storeId, "{}", now);
            submissionRepository.closeCart(storeId, cartId);
            entities.flush();
            entities.clear();
            submissionRepository.insertExpense(storeId, submission, stockId, 3, new BigDecimal("120.00"), now);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(stockQuantity()).isEqualTo(10);
        assertThat(cartVersion()).isZero();
        assertThat(countRows("SubmissionEntity")).isZero();
        assertThat(countRows("StockExpenseEntity")).isZero();
        assertThat(countRows("OutboxEntity")).isZero();
        assertThat(readDb(() -> carts.findById(cartId).orElseThrow().getState())).isEqualTo("OPEN");
    }

    /**
     * STORE-JPA-005. Две корзины одновременно оформляют последнюю единицу в отдельных транзакциях.
     * Один запрос принимается, второй получает нехватку; отрицательного и двойного расхода нет.
     */
    @Test
    void concurrentCheckoutHasOneWinnerForLastUnit() throws Exception {
        insertCart();
        insertLine(1);
        UUID otherCart = UUID.randomUUID();
        writeDb(() -> {
            stocks.findById(stockId).orElseThrow().setAvailableQuantity(1);
            carts.saveAndFlush(CartEntity.builder().cartId(otherCart).storeId(storeId).version(0).state("OPEN").createdAt(clock.instant()).build());
            items.saveAndFlush(CartItemEntity.builder().cartId(otherCart).stockItemId(stockId).storeId(storeId).quantity(1).build());
        });
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return submitResult(cartId, "first"); });
            var second = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return submitResult(otherCart, "second"); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "INSUFFICIENT_STOCK");
        }
        assertThat(stockQuantity()).isZero();
        assertThat(countRows("SubmissionEntity")).isEqualTo(1);
        assertThat(countRows("StockExpenseEntity")).isEqualTo(1);
        assertThat(countRows("OutboxEntity")).isEqualTo(1);
    }

    /** STORE-JPA-006. Два одновременных одинаковых запроса получают одну заявку и один расход. */
    @Test
    void concurrentSameKeyReturnsOneSubmission() throws Exception {
        insertCart();
        insertLine(3);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return submissions.submit(storeId, cartId, "same-key", new SubmitInput(0)); });
            var second = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return submissions.submit(storeId, cartId, "same-key", new SubmitInput(0)); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(countRows("SubmissionEntity")).isEqualTo(1);
        assertThat(countRows("StockExpenseEntity")).isEqualTo(1);
        assertThat(countRows("OutboxEntity")).isEqualTo(1);
    }

    /**
     * STORE-JPA-007. Первый работник держит строку outbox под блокировкой; второй пропускает её,
     * не ждёт освобождения и не получает событие. После commit событие снова доступно.
     */
    @Test
    void outboxSkipsRowLockedByAnotherTransaction() throws Exception {
        insertCart();
        insertLine(3);
        Submission accepted = submissions.submit(storeId, cartId, "skip-locked", new SubmitInput(0));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> writeDb(() -> {
                assertThat(outbox.lockDue(clock.instant(), clock.instant())).extracting(OutboxEntity::getEventId)
                        .containsExactly(accepted.eventId());
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var claimant = executor.submit(submissionTransactions::claimOutbox);
                assertThat(claimant.get(3, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(submissionTransactions.claimOutbox()).isPresent();
    }

    /**
     * STORE-JPA-008. Истёкшую попытку получает новый владелец; подтверждение старого токена не меняет статус.
     * Повтор захвата не списывает товар; публикация новым токеном сохраняет PUBLISHED в новом контексте.
     */
    @Test
    void expiredLeaseRejectsStaleAcknowledgement() {
        insertCart();
        insertLine(3);
        Submission accepted = submissions.submit(storeId, cartId, "lease", new SubmitInput(0));
        OutboxWork old = submissionTransactions.claimOutbox().orElseThrow();
        assertThat(submissionTransactions.claimOutbox()).isEmpty();
        writeDb(() -> outbox.findById(accepted.eventId()).orElseThrow().setLeaseUntil(clock.instant().minusSeconds(1)));
        OutboxWork current = submissionTransactions.claimOutbox().orElseThrow();
        assertThat(current.eventId()).isEqualTo(old.eventId());
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(current.attemptCount()).isEqualTo(2);
        submissionTransactions.published(old);
        assertThat(submissionTransactions.view(storeId, accepted.submissionId()).publicationStatus()).isEqualTo("PENDING");
        submissionTransactions.published(current);
        assertThat(submissionTransactions.view(storeId, accepted.submissionId()).publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(countRows("StockExpenseEntity")).isEqualTo(1);
    }

    /** STORE-JPA-009. Отказ отправки освобождает токен и назначает backoff без повторного списания. */
    @Test
    void failedSendSchedulesRetryWithoutNewExpense() {
        insertCart();
        insertLine(3);
        Submission accepted = submissions.submit(storeId, cartId, "retry", new SubmitInput(0));
        OutboxWork work = submissionTransactions.claimOutbox().orElseThrow();
        submissionTransactions.failedSend(work);
        assertThat(readDb(() -> outbox.findById(accepted.eventId()).orElseThrow().getLeaseToken())).isNull();
        assertThat(readDb(() -> outbox.findById(accepted.eventId()).orElseThrow().getNextAttemptAt()))
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(submissionTransactions.claimOutbox()).isEmpty();
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(countRows("StockExpenseEntity")).isEqualTo(1);
    }

    /**
     * STORE-JPA-010. Первое оформление держит незакоммиченный UNIQUE ключ; другая корзина с отдельным
     * остатком доходит до конфликтующей вставки. После commit победителя Hibernate выдаёт 23505,
     * проигравшая транзакция откатывается, а чтение в REQUIRES_NEW возвращает IDEMPOTENCY_KEY_REUSED.
     */
    @Test
    void uniqueKeyRaceRollsBackAndReadsWinnerInNewTransaction() throws Exception {
        insertCart();
        insertLine(3);
        UUID otherCart = UUID.randomUUID();
        UUID otherStock = UUID.randomUUID();
        UUID acceptedId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        writeDb(() -> {
            stocks.saveAndFlush(InventoryEntity.builder().stockItemId(otherStock).storeId(storeId).productId("P-2")
                    .productType("NON_FOOD").shortName("Другой товар").description("").unitPrice(new BigDecimal("120.00"))
                    .currency("RUB").availableQuantity(10).lastDeliverySequence(1).build());
            carts.saveAndFlush(CartEntity.builder().cartId(otherCart).storeId(storeId).state("OPEN").version(0).createdAt(clock.instant()).build());
            items.saveAndFlush(CartItemEntity.builder().cartId(otherCart).stockItemId(otherStock).storeId(storeId).quantity(3).build());
        });
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var blockingPid = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = executor.submit(() -> writeDb(() -> {
                blockingPid.set(((Number) entities.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue());
                Timestamp now = Timestamp.from(clock.instant());
                Cart snapshot = new Cart(storeId, cartId, 1, "SUBMITTED",
                        List.of(new CartLine(stockId, "P-1", "Мыло", 3, "120.00", "360.00")), "360.00", "RUB", acceptedId);
                submissionRepository.lockCart(storeId, cartId);
                submissionRepository.lockStockLines(storeId, cartId);
                submissionRepository.insertSubmission(acceptedId, storeId, cartId, "race-key",
                        codec.submissionFingerprint(storeId, cartId, 0), 0, eventId, now, codec.json(snapshot));
                submissionRepository.deductStock(3, storeId, stockId);
                submissionRepository.insertExpense(storeId, acceptedId, stockId, 3, new BigDecimal("120.00"), now);
                submissionRepository.insertOutbox(eventId, acceptedId, storeId, "{}", now);
                submissionRepository.closeCart(storeId, cartId);
                entities.flush();
                inserted.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                var loser = executor.submit(() -> submitResult(otherCart, "race-key"));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() -> readDb(() ->
                        ((Number) entities.createNativeQuery("""
                                select count(*) from pg_stat_activity
                                where :blocker = any(pg_blocking_pids(pid)) and query ilike '%insert into submissions%'
                                """).setParameter("blocker", blockingPid.get()).getSingleResult()).longValue()) == 1);
                release.countDown();
                winner.get(5, TimeUnit.SECONDS);
                assertThat(loser.get(5, TimeUnit.SECONDS)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            } finally {
                release.countDown();
            }
        }
        assertThat(stockQuantity()).isEqualTo(7);
        assertThat(readDb(() -> stocks.findById(otherStock).orElseThrow().getAvailableQuantity())).isEqualTo(10);
        assertThat(readDb(() -> carts.findById(otherCart).orElseThrow().getState())).isEqualTo("OPEN");
        assertThat(countRows("SubmissionEntity")).isEqualTo(1);
        assertThat(countRows("StockExpenseEntity")).isEqualTo(1);
        assertThat(countRows("OutboxEntity")).isEqualTo(1);
    }

    /**
     * Создаёт явное GoodsPosted для своего продукта с заданной ценой и порядком приёмки.
     * @param deliveryId поставка
     * @param sequence порядок первой приёмки
     * @param purchasePrice закупочная цена
     * @param salePrice проверяемая продажная цена при наценке 20 процентов
     * @param quantity количество прихода
     * @return независимое событие с новым eventId
     */
    private GoodsEvent delivery(String deliveryId, long sequence, String purchasePrice, String salePrice, int quantity) {
        PostedLine line = new PostedLine("L-1", "P-1", "NON_FOOD", "Мыло", "Описание", quantity,
                purchasePrice, "RUB", "0.20", UUID.randomUUID(), 1, salePrice);
        PostedPayload payload = new PostedPayload(deliveryId, sequence, clock.instant(), clock.instant(), List.of(line));
        return new GoodsEvent(UUID.randomUUID(), "GoodsPosted", 1, clock.instant(), storeId, payload);
    }

    /**
     * Возвращает бизнес-результат конкурентного оформления, не скрывая неожиданных ошибок БД.
     * @param cart корзина
     * @param key ключ повтора
     * @return ACCEPTED или код ShopException
     */
    private String submitResult(UUID cart, String key) {
        try {
            submissions.submit(storeId, cart, key, new SubmitInput(0));
            return "ACCEPTED";
        } catch (ShopException failure) {
            return failure.code();
        }
    }

    /**
     * Считает записи своего магазина в новом контексте; используется для атомарности и дедупликации.
     * @param entity имя проверяемой JPA-сущности из сценария
     * @return число сохранённых записей
     */
    private long countRows(String entity) {
        return readDb(() -> entities.createQuery("select count(e) from " + entity + " e where e.storeId = :store", Long.class)
                .setParameter("store", storeId).getSingleResult());
    }

    /**
     * Фиксирует подготовку или прямое изменение через JPA; откат при ошибке не затрагивает другие тесты.
     * @param action действие над управляемыми сущностями своего магазина
     */
    private void writeDb(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    /**
     * Читает состояние после завершения бизнес-операции в новом контексте JPA без кеша предыдущего вызова.
     * @param action чтение скалярных значений или DTO
     * @param <T> тип результата
     * @return значение, прочитанное из PostgreSQL
     */
    private <T> T readDb(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }
}
