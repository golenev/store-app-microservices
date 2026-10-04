package helpers

import awaitState
import required
import config.Database
import config.HttpClient
import config.Reply
import constants.Endpoints
import models.*
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** A pair of store/city fixture keys, not a new two-field preparation DTO. */
typealias Scope = Pair<String, String>
/** Identifies the STORE/WAREHOUSE owner of this fixture. */
val Scope.store: String get() = first
/** Identifies the tariff dimension of this fixture. */
val Scope.city: String get() = second

/** Scoped resource operations; transport details are nested below the scenario's business steps. */
object Shop {
    private val producer = testUtil.KafkaProducerImpl()

    /** Refuses ordinary Compose projects before SQL fixtures or container controls can run. */
    fun project(): String {
        val project = System.getenv("E2E_COMPOSE_PROJECT") ?: error("Use scripts/run-e2e.py: E2E_COMPOSE_PROJECT is required")
        require(project.matches(Regex("shop-e2e-[a-z0-9-]+"))) { "Refusing non-E2E project: $project" }
        return project
    }

    /** Builds immutable supplier input with independent IDs and exact monetary strings. */
    fun delivery(scope: Scope, product: String = "P-${UUID.randomUUID()}", quantity: Int = 10,
                 price: String = "100.00", type: String = "NON_FOOD"): DeliveryReceived {
        return DeliveryReceived(eventId = UUID.randomUUID().toString(), occurredAt = Instant.now().toString(), storeId = scope.store,
            payload = DeliveryPayload("D-${UUID.randomUUID()}", listOf(DeliveryLine("L-1", product, type, "Учебный $product", "E2E", quantity, price))))
    }

    /** Publishes through the supplier API; broker acknowledgement does not substitute for pricing/STORE consumption. */
    fun publish(event: DeliveryReceived) {
        HttpClient.request(Endpoints.WAREHOUSE, "/technical/deliveries", "POST", event).expect(202)
    }

    /** Publishes exact valid or deliberately invalid text with the explicit transport key. */
    fun kafka(topic: String, store: String, raw: String?) {
        step("Kafka publish: topic=$topic, key=$store") { producer.sendMessage(topic, store, raw) }
    }

    /** Reads receiving status; only 404 is a legitimate pre-consumption absence. */
    fun receiving(event: DeliveryReceived): Receiving? {
        val reply = HttpClient.request(Endpoints.WAREHOUSE, "/stores/${event.storeId}/deliveries/${event.payload.deliveryId}")
        if (reply.status == 404) return null
        return reply.expect(200).body()
    }

    /** Waits for the requested state of the same delivery; read/parse errors propagate rather than becoming timeout. */
    fun received(event: DeliveryReceived, state: DeliveryState = DeliveryState.POSTED): Receiving {
        val result = awaitState("delivery=${event.payload.deliveryId}, store=${event.storeId}, expected=$state",
            read = { receiving(event) }, ready = { it?.state == state })
        return required(result, "receiving ${event.payload.deliveryId}")
    }

    /** Reads a typed scoped catalog; no ordering or arbitrary first-item assumption is made. */
    fun catalog(scope: Scope): Catalog {
        return HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/catalog").expect(200).body()
    }

    /** Resolves one product by identity and rejects duplicate positions explicitly. */
    fun stock(scope: Scope, product: String): Stock? {
        val matching = catalog(scope).items.filter { it.productId == product }
        assertTrue(matching.size <= 1, "Duplicate product=$product, store=${scope.store}: $matching")
        return matching.singleOrNull()
    }

    /** Requires an existing product and reports its key on failure instead of throwing NPE. */
    fun requireStock(scope: Scope, product: String): Stock {
        return required(stock(scope, product), "inventory product=$product, store=${scope.store}")
    }

    /** Waits for the explicit expected quantity; WAREHOUSE POSTED alone is not a STORE commit. */
    fun stocked(scope: Scope, event: DeliveryReceived, expectedQuantity: Int = event.payload.items.single().quantity): Stock {
        val product = event.payload.items.single().productId
        val result = awaitState("STORE product=$product, expected quantity=$expectedQuantity",
            read = { stock(scope, product) }, ready = { it?.availableQuantity == expectedQuantity })
        return required(result, "inventory $product")
    }

    /** Performs real supply preparation; callers state non-default fixture values before this action. */
    fun supply(scope: Scope, event: DeliveryReceived = delivery(scope)): Stock {
        publish(event)
        received(event)
        return stocked(scope, event)
    }

    /** Creates one independent server-side cart. */
    fun cart(scope: Scope): Cart {
        return HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts", "POST").expect(201).body()
    }

    /** Reads a current cart or its immutable accepted snapshot by the original identity. */
    fun getCart(scope: Scope, cart: Cart): Cart {
        return HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts/${cart.cartId}").expect(200).body()
    }

    /** Replaces an absolute quantity; caller explicitly asserts success or the expected negative status. */
    fun put(scope: Scope, cart: Cart, stock: Stock, quantity: Int): Reply {
        return HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts/${cart.cartId}/items/${stock.stockItemId}",
            "PUT", PutCartItem(quantity, cart.version))
    }

    /** Prepares an independent filled cart through public versioned operations, without reserving inventory. */
    fun filled(scope: Scope, stock: Stock, quantity: Int): Cart {
        return put(scope, cart(scope), stock, quantity).expect(200).body()
    }

    /** Sends the captured cart version and stable key; never reads a newer version to disguise a replay. */
    fun submit(scope: Scope, cart: Cart, key: String = UUID.randomUUID().toString()): Reply {
        return HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/carts/${cart.cartId}/submit", "POST", SubmitCart(cart.version), key)
    }

    /** Waits for acknowledgement of an accepted operation without re-running its expense transaction. */
    fun published(scope: Scope, accepted: Submission): Submission {
        return awaitState("submission=${accepted.submissionId}, expected=PUBLISHED", read = {
            HttpClient.request(Endpoints.STORE, "/stores/${scope.store}/submissions/${accepted.submissionId}").expect(200).body<Submission>()
        }, ready = { it.publicationStatus == PublicationStatus.PUBLISHED })
    }

    /** Reads the immutable persisted output; used as a baseline only for replay/snapshot invariants. */
    fun goods(event: DeliveryReceived): GoodsPosted {
        val raw = required(Database.scalar("warehouse", "SELECT payload FROM warehouse_outbox WHERE store_id=? AND delivery_id=?",
            event.storeId, event.payload.deliveryId), "GoodsPosted ${event.payload.deliveryId}")
        return HttpClient.mapper.readValue(raw)
    }

    /** Waits for a real worker acknowledgement of a store-scoped gate. */
    fun reached(service: String, scope: Scope, point: String): String {
        val result = awaitState("$service store=${scope.store} reached $point", read = {
            Database.scalar(service, "SELECT reached_subject FROM e2e_gates WHERE store_id=? AND point=? AND hits>0", scope.store, point)
        }, ready = { it != null })
        return required(result, "$service gate=$point")
    }

    /** Releases only the gates owned by this scenario; claims recover at their persisted lease deadline. */
    fun release(service: String, scope: Scope) {
        Database.update(service, "UPDATE e2e_gates SET blocked=FALSE WHERE store_id=?", scope.store)
    }

    /** Controls a whitelisted service in the dedicated project, recording diagnostics and killing timed-out child processes. */
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

    /** Waits for startup health; expected connection-refused windows remain visible in the timeout state. */
    fun healthy(base: String) {
        awaitState("health $base", read = {
            try { HttpClient.request(base, "/actuator/health").status.toString() }
            catch (failure: java.io.IOException) { "startup: ${failure.message}" }
        }, ready = { it == "200" })
    }

    /** Runs two explicit operations at one barrier; propagates failures and confirms executor termination. */
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

    /** Checks conservation and a saved outbox for each accepted operation, independently of HTTP rendering. */
    fun audit(scope: Scope) {
        assertEquals("0", Database.scalar("store", "SELECT count(*) FROM inventory i WHERE store_id=? AND available_quantity<>(SELECT COALESCE(sum(quantity),0) FROM stock_movements m WHERE m.stock_item_id=i.stock_item_id)-(SELECT COALESCE(sum(quantity),0) FROM stock_expenses e WHERE e.stock_item_id=i.stock_item_id)", scope.store), "Stock conservation store=${scope.store}")
        assertEquals("0", Database.scalar("store", "SELECT count(*) FROM submissions s LEFT JOIN store_outbox o ON o.submission_id=s.submission_id WHERE s.store_id=? AND o.event_id IS NULL", scope.store), "Missing accepted outbox store=${scope.store}")
    }
}
