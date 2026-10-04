package helpers

import awaitState
import com.fasterxml.jackson.databind.JsonNode
import config.Database
import config.HttpClient
import config.Reply
import constants.Endpoints
import models.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Every fault-capable scenario verifies the launcher's dedicated project before accessing SQL or controlling containers. */
abstract class ShopE2E {
    /** Rejects missing/ordinary Compose projects and requires actual e2e-only tables in both service databases. */
    @BeforeEach fun verifyIsolation() {
        Shop.project()
        for (service in listOf("store", "warehouse")) assertEquals("e2e_gates", Database.scalar(service, "SELECT to_regclass('e2e_gates')::text"))
    }
}

/** Fresh scope fixture; only ownership/city mapping is seeded, while inventory is always created through public delivery ingress. */
data class Scope(val store: String, val city: String)

/** Explicit low-level test actions; scenario methods keep their own setup, fault windows and assertions. */
object Shop {
    private val producer = testUtil.KafkaProducerImpl()

    /** Resolves and validates only the dedicated launcher project, refusing user/default Compose environments. */
    fun project(): String = (System.getenv("E2E_COMPOSE_PROJECT") ?: error("Run the isolated E2E launcher; E2E_COMPOSE_PROJECT is required"))
        .also { require(it.matches(Regex("shop-e2e-[a-z0-9-]+"))) { "Refusing to control a non-E2E Compose project" } }

    /** Creates independent store IDs and optional city fixture; does not insert stock, cart items, receipts or expenses. */
    fun scope(city: String = "MOSCOW"): Scope {
        project(); val store = "S-${UUID.randomUUID()}"
        Database.update("warehouse", "INSERT INTO stores(store_id,city) VALUES (?,?)", store, city)
        Database.update("store", "INSERT INTO store_scopes(store_id) VALUES (?)", store)
        return Scope(store, city)
    }

    /** Builds one immutable supplier event with independent IDs; purchasePrice stays an exact decimal string. */
    fun delivery(scope: Scope, product: String = "P-${UUID.randomUUID()}", quantity: Int = 10, price: String = "100.00", type: String = "NON_FOOD"): DeliveryReceived =
        DeliveryReceived(eventId = UUID.randomUUID().toString(), occurredAt = Instant.now().toString(), storeId = scope.store,
            payload = DeliveryPayload("D-${UUID.randomUUID()}", listOf(DeliveryLine("L-1", product, type, "Учебный $product", "E2E", quantity, price))))

    /** Publishes through the educational HTTP ingress and requires actual Kafka acknowledgement; POSTED is checked separately. */
    fun publish(event: DeliveryReceived) { assertEquals(202, HttpClient.request(Endpoints.WAREHOUSE, "/technical/deliveries", "POST", event).status) }

    /** Sends exact test text with a store-scoped Kafka key, including invalid schema/raw payload tests. */
    fun kafka(topic: String, store: String, raw: String?) { producer.sendMessage(topic, store, raw) }

    /** Reads store-scoped receiving diagnostics; transient absence is returned as an HTTP status, not synthetic data. */
    fun receiving(event: DeliveryReceived): Reply = HttpClient.request(Endpoints.WAREHOUSE, "/stores/${event.storeId}/deliveries/${event.payload.deliveryId}")

    /** Waits for committed receiving state and returns its real metadata/price result. */
    fun received(event: DeliveryReceived, state: String = "POSTED"): JsonNode = awaitState("delivery ${event.payload.deliveryId}: $state",
        read = { receiving(event) }, ready = { it.status == 200 && it.json.path("state").asText() == state }).json

    /** Reads only the current scope's catalog and requires a successful new API response. */
    fun catalog(scope: Scope): JsonNode = HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/catalog").also { assertEquals(200, it.status) }.json

    /** Resolves this scenario's product without assuming catalog order or another test's quantities. */
    fun stock(scope: Scope, product: String): JsonNode? = catalog(scope).path("items").firstOrNull { it.path("productId").asText() == product }

    /** Waits for STORE consumption/quantity independently of WAREHOUSE's POSTED state. */
    fun stocked(scope: Scope, event: DeliveryReceived, quantity: Int = event.payload.items.single().quantity): JsonNode =
        awaitState("inventory ${event.payload.items.single().productId}=$quantity", read = { stock(scope, event.payload.items.single().productId) },
            ready = { it?.path("availableQuantity")?.asInt() == quantity })!!

    /** Executes a complete real supply path before returning the committed STORE position. */
    fun supply(scope: Scope, event: DeliveryReceived = delivery(scope)): JsonNode { publish(event); received(event); return stocked(scope, event) }

    /** Creates an independent server cart in this scope; no identity is reused across scenarios. */
    fun cart(scope: Scope): JsonNode = HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts", "POST").also { assertEquals(201, it.status) }.json

    /** Reads a current cart or its immutable accepted snapshot. */
    fun getCart(scope: Scope, cart: JsonNode): JsonNode = HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts/${cart.path("cartId").asText()}").also { assertEquals(200, it.status) }.json

    /** Replaces one line's absolute quantity using this supplied cart version; callers inspect negative responses explicitly. */
    fun put(scope: Scope, cart: JsonNode, stock: JsonNode, quantity: Int): Reply = HttpClient.request(Endpoints.STORE,
        "/stores/${scope.store}/carts/${cart.path("cartId").asText()}/items/${stock.path("stockItemId").asText()}", "PUT",
        mapOf("quantity" to quantity, "expectedCartVersion" to cart.path("version").asLong()))

    /** Adds an accepted versioned line and returns the actual full server cart. */
    fun filled(scope: Scope, stock: JsonNode, quantity: Int): JsonNode = put(scope, cart(scope), stock, quantity).also { assertEquals(200, it.status) }.json

    /** Submits only the captured original version and explicit key; never rewrites a replay using the closed cart's version. */
    fun submit(scope: Scope, cart: JsonNode, key: String = UUID.randomUUID().toString()): Reply = HttpClient.request(Endpoints.STORE,
        "/stores/${scope.store}/carts/${cart.path("cartId").asText()}/submit", "POST", mapOf("expectedCartVersion" to cart.path("version").asLong()), key)

    /** Waits for real broker acknowledgement of an already committed submission, without retrying its expense transaction. */
    fun published(scope: Scope, accepted: JsonNode): JsonNode = awaitState("submission PUBLISHED", read = {
        HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/submissions/${accepted.path("submissionId").asText()}").json
    }, ready = { it.path("publicationStatus").asText() == "PUBLISHED" })

    /** Reads the exact immutable GoodsPosted payload written by WAREHOUSE; no invented direct fixture bypasses pricing. */
    fun goods(event: DeliveryReceived): JsonNode = HttpClient.mapper.readTree(Database.scalar("warehouse",
        "SELECT payload FROM warehouse_outbox WHERE store_id=? AND delivery_id=?", event.storeId, event.payload.deliveryId)!!)

    /** Installs a store/subject-scoped persisted gate in an e2e-only control table before triggering the controlled boundary. */
    fun gate(service: String, scope: Scope, point: String, subject: String = "*") {
        Database.update(service, "INSERT INTO e2e_gates(store_id,subject_id,point) VALUES (?,?,?)", scope.store, subject, point)
    }

    /** Waits for the real worker to acknowledge a gate, rather than guessing where it is from elapsed time. */
    fun reached(service: String, scope: Scope, point: String): String = awaitState("$service reached $point",
        read = { Database.scalar(service, "SELECT reached_subject FROM e2e_gates WHERE store_id=? AND point=? AND hits>0", scope.store, point) }, ready = { it != null })!!

    /** Releases only this scope's gates; persisted claims recover naturally after their original lease deadline. */
    fun release(service: String, scope: Scope) { Database.update(service, "UPDATE e2e_gates SET blocked=FALSE WHERE store_id=?", scope.store) }

    /** Controls only known services/actions inside the validated owned project; no shell interpolation, volume removal or arbitrary Docker target is allowed. */
    fun control(action: String, service: String) {
        require(action in setOf("stop", "start", "restart", "pause", "unpause"))
        require(service in setOf("store-service", "warehouse-service", "tariffs-service", "kafka", "redis", "postgres"))
        val root = File("..").canonicalFile
        val command = mutableListOf("docker", "compose", "-p", project(), "-f", File(root, "docker-compose.yml").path, "-f", File(root, "compose.e2e.yml").path)
        System.getenv("E2E_EXTRA_COMPOSE_FILE")?.let { command.addAll(listOf("-f", it)) }
        // Compose start rechecks transient dependency health after pause; starting only the selected existing app avoids that unrelated gate.
        command.addAll(if(action=="start") listOf("up","-d","--no-deps","--no-build",service) else listOf(action,service))
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(root, "runtime-task8-control.log"))).start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Docker control timed out: $action $service" }
        check(process.exitValue() == 0) { "Docker control failed; see runtime-task8-control.log" }
    }

    /** Waits for application health after restart; expected connection-refused startup windows are retried within a deadline. */
    fun healthy(base: String) { awaitState("health $base", read = {
        try { HttpClient.request(base, "/actuator/health").status == 200 } catch (failure: java.io.IOException) { false }
    }, ready = { it }) }

    /** Starts both actions at one barrier and returns both real outcomes; failures propagate instead of being lost in executor threads. */
    fun <T> race(first: () -> T, second: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(2); val barrier = CyclicBarrier(2)
        try { return listOf(first, second).map { action -> pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); action() }) }.map { it.get(30, TimeUnit.SECONDS) } }
        finally { pool.shutdownNow() }
    }

    /** Reads physical Kafka copies for one immutable event from the owned topic's beginning, proving ack loss causes identical replay. */
    fun events(topic: String, eventId: String, minimum: Int): List<JsonNode> {
        val properties = mapOf<String, Any>("bootstrap.servers" to Endpoints.KAFKA, "group.id" to "e2e-${UUID.randomUUID()}",
            "enable.auto.commit" to false, "key.deserializer" to StringDeserializer::class.java, "value.deserializer" to StringDeserializer::class.java)
        KafkaConsumer<String, String>(properties).use { consumer ->
            val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            consumer.assign(partitions); consumer.seekToBeginning(partitions)
            val matching = mutableListOf<JsonNode>()
            return awaitState("$minimum Kafka copies $eventId", read = {
                for (record in consumer.poll(Duration.ofMillis(100))) {
                    if (record.value() != null) {
                        val json = HttpClient.mapper.readTree(record.value())
                        if (json.path("eventId").asText() == eventId) matching.add(json)
                    }
                }
                matching.toList()
            }, ready = { it.size >= minimum })
        }
    }

    /** Waits until the actual consumer commits through every currently acknowledged record; negative duplicate checks cannot pass before processing. */
    fun drained(topic: String, group: String) {
        AdminClient.create(mapOf("bootstrap.servers" to Endpoints.KAFKA)).use { admin ->
            val partitions = admin.describeTopics(listOf(topic)).allTopicNames().get(10, TimeUnit.SECONDS)[topic]!!.partitions().map { TopicPartition(topic,it.partition()) }
            val ends=admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(10,TimeUnit.SECONDS)
            awaitState("consumer $group committed through $topic", read = { admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS) },
                ready = { offsets -> ends.all { (partition,end) -> (offsets[partition]?.offset() ?: -1) >= end.offset() } })
        }
    }

    /** Verifies every SKU in this scope equals unique credits minus accepted debits and every accepted operation has one saved outbox. */
    fun audit(scope: Scope) {
        assertEquals("0", Database.scalar("store", "SELECT count(*) FROM inventory i WHERE store_id=? AND available_quantity<>(SELECT COALESCE(sum(quantity),0) FROM stock_movements m WHERE m.stock_item_id=i.stock_item_id)-(SELECT COALESCE(sum(quantity),0) FROM stock_expenses e WHERE e.stock_item_id=i.stock_item_id)", scope.store))
        assertEquals("0", Database.scalar("store", "SELECT count(*) FROM submissions s LEFT JOIN store_outbox o ON o.submission_id=s.submission_id WHERE s.store_id=? AND o.event_id IS NULL", scope.store))
    }
}
