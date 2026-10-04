package org.golenev.utils

import org.golenev.utils.awaitState
import org.golenev.utils.required
import org.golenev.db.tables.observation.ObservationDao
import org.golenev.utils.JsonUtils
import org.golenev.restapi.endpoints.*
import org.golenev.restapi.config.ApiServices
import org.golenev.restapi.config.Reply
import org.golenev.commondto.*
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Пара идентификаторов магазина и города для подготовки теста. */
typealias Scope = Pair<String, String>
/** Возвращает идентификатор магазина — владельца данных STORE и WAREHOUSE. */
val Scope.store: String get() = first
/** Возвращает город, определяющий выбор тарифного правила. */
val Scope.city: String get() = second

/** Операции с ресурсами конкретного магазина. Подробности транспорта записываются во вложенные шаги бизнес-сценария. */
object Shop {
    private val warehouseService = WarehouseServiceDao()

    private val storeService = StoreServiceDao()

    private val producer = org.golenev.utils.kafka.KafkaProducerImpl()

    /** Проверяет принадлежность Compose-проекта E2E до создания SQL-данных или управления контейнерами. */
    fun project(): String {
        val project = System.getenv("E2E_COMPOSE_PROJECT") ?: error("Use scripts/run-e2e.py: E2E_COMPOSE_PROJECT is required")
        require(project.matches(Regex("shop-e2e-[a-z0-9-]+"))) { "Refusing non-E2E project: $project" }
        return project
    }

    /** Создаёт неизменяемые данные поставки с независимыми идентификаторами и точными денежными строками. */
    fun delivery(scope: Scope, product: String = "P-${UUID.randomUUID()}", quantity: Int = 10,
                 price: String = "100.00", type: String = "NON_FOOD"): DeliveryReceived {
        return DeliveryReceived(eventId = UUID.randomUUID().toString(), occurredAt = Instant.now().toString(), storeId = scope.store,
            payload = DeliveryPayload("D-${UUID.randomUUID()}", listOf(DeliveryLine("L-1", product, type, "Учебный $product", "E2E", quantity, price))))
    }

    /** Передаёт поставку через API поставщика. Подтверждение Kafka ещё не означает расчёт цены или зачисление в STORE. */
    fun publish(event: DeliveryReceived) {
        warehouseService.sendDelivery(event).expect(202)
    }

    /** Публикует исходный корректный или намеренно некорректный текст с явно заданным ключом Kafka. */
    fun kafka(topic: String, store: String, raw: String?) {
        step("Публикуем в Kafka: топик $topic, ключ $store") { producer.sendMessage(topic, store, raw) }
    }

    /** Читает состояние приёмки. Только HTTP 404 означает, что поставка ещё не обработана; прочие ошибки не скрываются. */
    fun receiving(event: DeliveryReceived): Receiving? {
        val reply = warehouseService.getDelivery(event.storeId, event.payload.deliveryId)
        if (reply.status == 404) return null
        return reply.expect(200).body()
    }

    /** Ждёт заданное состояние той же поставки. Ошибки чтения и разбора передаются сразу, а не превращаются в таймаут. */
    fun received(event: DeliveryReceived, state: DeliveryState = DeliveryState.POSTED): Receiving {
        val result = awaitState("delivery=${event.payload.deliveryId}, store=${event.storeId}, expected=$state",
            read = { receiving(event) }, ready = { it?.state == state })
        return required(result, "receiving ${event.payload.deliveryId}")
    }

    /** Возвращает типизированный каталог магазина. Порядок позиций не предполагается. */
    fun catalog(scope: Scope): Catalog {
        return storeService.getCatalog(scope.store).expect(200).body()
    }

    /** Находит позицию по productId и явно отклоняет несколько позиций одного продукта. */
    fun stock(scope: Scope, product: String): Stock? {
        val matching = catalog(scope).items.filter { it.productId == product }
        assertTrue(matching.size <= 1, "Duplicate product=$product, store=${scope.store}: $matching")
        return matching.singleOrNull()
    }

    /** Возвращает существующий товар или завершает проверку с его идентификатором, вместо NullPointerException. */
    fun requireStock(scope: Scope, product: String): Stock {
        return required(stock(scope, product), "inventory product=$product, store=${scope.store}")
    }

    /** Ждёт явно ожидаемое количество в STORE. Одного состояния POSTED в WAREHOUSE для этого недостаточно. */
    fun stocked(scope: Scope, event: DeliveryReceived, expectedQuantity: Int = event.payload.items.single().quantity): Stock {
        val product = event.payload.items.single().productId
        val result = awaitState("STORE product=$product, expected quantity=$expectedQuantity",
            read = { stock(scope, product) }, ready = { it?.availableQuantity == expectedQuantity })
        return required(result, "inventory $product")
    }

    /** Готовит остаток через настоящую поставку. Отличающиеся от стандартных данные вызывающий код задаёт до действия. */
    fun supply(scope: Scope, event: DeliveryReceived = delivery(scope)): Stock {
        publish(event)
        received(event)
        return stocked(scope, event)
    }

    /** Создаёт отдельную серверную корзину магазина. */
    fun cart(scope: Scope): Cart {
        return storeService.createCart(scope.store).expect(201).body()
    }

    /** Читает текущую корзину либо неизменяемый состав принятой заявки по исходному идентификатору. */
    fun getCart(scope: Scope, cart: Cart): Cart {
        return storeService.getCart(scope.store, cart.cartId).expect(200).body()
    }

    /** Устанавливает количество позиции. Ожидаемый статус успеха или отказа явно проверяет вызывающий сценарий. */
    fun put(scope: Scope, cart: Cart, stock: Stock, quantity: Int): Reply {
        return storeService.putCartItem(scope.store, cart.cartId, stock.stockItemId, PutCartItem(quantity, cart.version))
    }

    /** Создаёт и наполняет независимую корзину через публичный API с проверкой версии. Остаток не резервируется. */
    fun filled(scope: Scope, stock: Stock, quantity: Int): Cart {
        return put(scope, cart(scope), stock, quantity).expect(200).body()
    }

    /** Отправляет сохранённую версию корзины и постоянный ключ операции. Новая версия не подставляется вместо исходного повторного запроса. */
    fun submit(scope: Scope, cart: Cart, key: String = UUID.randomUUID().toString()): Reply {
        return storeService.submitCart(scope.store, cart.cartId, SubmitCart(cart.version), key)
    }

    /** Ждёт подтверждение публикации принятой заявки, не повторяя транзакцию списания. */
    fun published(scope: Scope, accepted: Submission): Submission {
        return awaitState("submission=${accepted.submissionId}, expected=PUBLISHED", read = {
            storeService.getSubmission(scope.store, accepted.submissionId).expect(200).body<Submission>()
        }, ready = { it.publicationStatus == PublicationStatus.PUBLISHED })
    }

    /** Читает сохранённое неизменяемое исходящее событие. Оно служит исходным снимком для проверки повторов и сохранности данных. */
    fun goods(event: DeliveryReceived): GoodsPosted {
        val raw = required(ObservationDao.scalar("warehouse", "SELECT payload FROM warehouse_outbox WHERE store_id=? AND delivery_id=?",
            event.storeId, event.payload.deliveryId), "GoodsPosted ${event.payload.deliveryId}")
        return JsonUtils.objectMapper.readValue(raw)
    }

    /** Ждёт подтверждение, что обработчик достиг тестовой задержки своего магазина. */
    fun reached(service: String, scope: Scope, point: String): String {
        val result = awaitState("$service store=${scope.store} reached $point", read = {
            ObservationDao.scalar(service, "SELECT reached_subject FROM e2e_gates WHERE store_id=? AND point=? AND hits>0", scope.store, point)
        }, ready = { it != null })
        return required(result, "$service gate=$point")
    }

    /** Снимает только задержки этого сценария. Захваченная работа восстанавливается по сохранённому сроку её удержания. */
    fun release(service: String, scope: Scope) {
        ObservationDao.update(service, "UPDATE e2e_gates SET blocked=FALSE WHERE store_id=?", scope.store)
    }

    /** Управляет разрешённым сервисом собственного Compose-проекта. Сохраняет диагностику и завершает дочерний процесс при таймауте. */
    fun control(action: String, service: String) {
        require(action in setOf("stop", "start", "restart", "pause", "unpause"))
        require(service in setOf("store-service", "warehouse-service", "tariffs-service", "kafka", "redis", "postgres"))
        val root = File("..").canonicalFile
        val command = mutableListOf("docker", "compose", "-p", project(), "-f", File(root, "docker-compose.yml").path, "-f", File(root, "compose.e2e.yml").path)
        System.getenv("E2E_EXTRA_COMPOSE_FILE")?.let { command.addAll(listOf("-f", it)) }
        command.addAll(if (action == "start") listOf("up", "-d", "--no-deps", "--no-build", service) else listOf(action, service))
        val process = ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(root, "runtime-e2e-control.log"))).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Docker child did not terminate: $command" }
            error("Docker control timed out: $command")
        }
        check(process.exitValue() == 0) { "Docker control failed ($command); see runtime-e2e-control.log" }
    }

    /** Ждёт готовность сервиса после запуска. Ожидаемый отказ подключения остаётся видимым в диагностике таймаута. */
    fun healthy(base: String) {
        awaitState("health $base", read = {
            try { ApiServices.request(base, "/actuator/health").status.toString() }
            catch (failure: java.io.IOException) { "startup: ${failure.message}" }
        }, ready = { it == "200" })
    }

    /** Запускает две операции через общий барьер. Передаёт их ошибки и проверяет завершение пула потоков. */
    fun <T> race(first: () -> T, second: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        var primary: Throwable? = null
        try {
            val futures = listOf(first, second).map { action -> pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); action() }) }
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            pool.shutdownNow()
            try {
                check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "Race executor did not terminate" }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    /** Проверяет сохранность количества товара и наличие исходящего сообщения у каждой принятой операции, независимо от HTTP-представления. */
    fun audit(scope: Scope) {
        assertEquals("0", ObservationDao.scalar("store", "SELECT count(*) FROM inventory i WHERE store_id=? AND available_quantity<>(SELECT COALESCE(sum(quantity),0) FROM stock_movements m WHERE m.stock_item_id=i.stock_item_id)-(SELECT COALESCE(sum(quantity),0) FROM stock_expenses e WHERE e.stock_item_id=i.stock_item_id)", scope.store), "Stock conservation store=${scope.store}")
        assertEquals("0", ObservationDao.scalar("store", "SELECT count(*) FROM submissions s LEFT JOIN store_outbox o ON o.submission_id=s.submission_id WHERE s.store_id=? AND o.event_id IS NULL", scope.store), "Missing accepted outbox store=${scope.store}")
    }
}
