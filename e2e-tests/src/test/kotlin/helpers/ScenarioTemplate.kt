package helpers

import config.Database
import config.HttpClient
import constants.Endpoints
import io.lettuce.core.RedisClient
import io.qameta.allure.Allure
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

/** Resource ownership ledger; cleanup actions are registered before operations which may partially succeed. */
class ScenarioResources : AutoCloseable {
    private val cleanup = mutableListOf<Pair<String, () -> Unit>>()
    private var kafkaPaused = false

    /** Registers an idempotent owned-resource restoration; close runs every action and aggregates errors. */
    fun onClose(description: String, action: () -> Unit) {
        cleanup += description to action
    }

    /** Captures the isolated tariff namespace, restoring its values with a fresh fence generation at teardown. */
    fun preserveCache() {
        val client = RedisClient.create("redis://localhost:${System.getenv("E2E_REDIS_PORT") ?: error("E2E_REDIS_PORT required")}")
        onClose("Close Redis client") { client.shutdown() }
        val connection = client.connect()
        onClose("Close Redis connection") { connection.close() }
        val commands = connection.sync()
        val snapshot = commands.hgetall("tariff-quotes:v1:entries").toMap()
        onClose("Restore tariff cache snapshot with a fresh generation") {
            commands.set("tariff-quotes:v1:epoch", UUID.randomUUID().toString())
            commands.del("tariff-quotes:v1:entries")
            if (snapshot.isNotEmpty()) commands.hset("tariff-quotes:v1:entries", snapshot)
        }
    }

    /** Allocates a new city key before its rule creation; no existing fixture rule is mutated or deleted. */
    fun city(): String {
        val city = "C-${UUID.randomUUID()}"
        onClose("Remove owned rules city=$city") { Database.update("tariffs", "DELETE FROM tariff_rules WHERE city_id=?", city) }
        return city
    }

    /** Registers the store key before seeding ownership; all inventory must subsequently arrive through the real supplier flow. */
    fun scope(city: String = "MOSCOW"): Scope {
        val store = "S-${UUID.randomUUID()}"
        onClose("Remove owned scope $store") { purgeScope(store) }
        Database.update("warehouse", "INSERT INTO stores(store_id,city) VALUES (?,?)", store, city)
        Database.update("store", "INSERT INTO store_scopes(store_id) VALUES (?)", store)
        return store to city
    }

    /** Opens and owns a ready observer before the action; startup failure closes the partially created consumer itself. */
    fun observe(topic: String, key: String): KafkaObservation {
        val observer = KafkaObservation.open(topic, key)
        onClose("Close observer topic=$topic key=$key") { observer.close() }
        return observer
    }

    /** Registers gate release before inserting it; a failed setup cannot leave a blocked worker behind. */
    fun gate(service: String, scope: Scope, point: String, subject: String = "*") {
        onClose("Release $service gate=$point store=${scope.store}") { Shop.release(service, scope) }
        Database.update(service, "INSERT INTO e2e_gates(store_id,subject_id,point) VALUES (?,?,?)", scope.store, subject, point)
    }

    /** Stops one owned service and registers startup before the stop operation can partially succeed. */
    fun stop(service: String, healthUrl: String? = null) {
        onClose("Restore service $service") {
            Shop.control("start", service)
            if (healthUrl != null) Shop.healthy(healthUrl)
        }
        Shop.control("stop", service)
    }

    /** Pauses only the owned broker and registers unpause before the fault is installed. */
    fun pauseKafka() {
        onClose("Resume owned broker") { resumeKafka() }
        kafkaPaused = true
        Shop.control("pause", "kafka")
    }

    /** Restores the fault this ledger owns once; explicit recovery and later cleanup are safely repeatable. */
    fun resumeKafka() {
        if (!kafkaPaused) return
        Shop.control("unpause", "kafka")
        kafkaPaused = false
    }

    /** Installs a unique store-scoped SQL trigger; both trigger and function cleanup survive partial installation. */
    fun failExpense(scope: Scope) {
        val name = "e2e_expense_${UUID.randomUUID().toString().replace("-", "")}"
        onClose("Remove scoped SQL fault $name") {
            Database.update("store", "DROP TRIGGER IF EXISTS $name ON stock_expenses")
            Database.update("store", "DROP FUNCTION IF EXISTS $name()")
        }
        Database.update("store", "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.store_id='${scope.store}' AND EXISTS(SELECT 1 FROM e2e_gates WHERE store_id=NEW.store_id AND point='SUBMIT_ROLLBACK' AND blocked) THEN RAISE EXCEPTION 'e2e controlled rollback'; END IF; RETURN NEW; END $$")
        Database.update("store", "CREATE TRIGGER $name BEFORE INSERT ON stock_expenses FOR EACH ROW EXECUTE FUNCTION $name()")
        gate("store", scope, "SUBMIT_ROLLBACK")
    }

    /** Removes only UUID-owned records in FK order; fixtures and external databases/volumes remain untouched. */
    private fun purgeScope(store: String) {
        for (table in listOf("warehouse_outbox", "received_events", "delivery_items", "deliveries", "e2e_gates")) {
            Database.update("warehouse", "DELETE FROM $table WHERE store_id=?", store)
        }
        Database.update("warehouse", "DELETE FROM delivery_diagnostics WHERE raw_message LIKE ?", "%$store%")
        Database.update("warehouse", "DELETE FROM stores WHERE store_id=?", store)
        for (table in listOf("store_outbox", "stock_expenses", "submissions", "cart_items", "carts", "stock_movements", "processed_events", "inventory", "stock_receipts", "e2e_gates")) {
            Database.update("store", "DELETE FROM $table WHERE store_id=?", store)
        }
        Database.update("store", "DELETE FROM incoming_goods_diagnostics WHERE raw_message LIKE ?", "%$store%")
        Database.update("store", "DELETE FROM store_scopes WHERE store_id=?", store)
    }

    /** Runs every restoration in reverse order; later cleanup failures are suppressed without hiding the first failure. */
    override fun close() {
        step("Завершаем сценарий и восстанавливаем исходное окружение") {
            var first: Throwable? = null
            for ((description, action) in cleanup.asReversed()) {
                try { step(description, action) }
                catch (failure: Throwable) {
                    if (first == null) first = failure else first.addSuppressed(failure)
                }
            }
            cleanup.clear()
            first?.let { throw it }
        }
    }
}

/** Runs the explicit scenario in a verified isolated environment; use preserves primary failure with suppressed cleanup errors. */
fun withShopTemplate(body: (ScenarioResources) -> Unit) {
    ScenarioResources().use { resources ->
        Allure.label("testRevision", System.getProperty("e2e.revision", "local"))
        Allure.label("testSourceHash", System.getProperty("e2e.testSourceHash", "local"))
        Allure.label("composeProject", Shop.project())
        step("Подготавливаем независимое тестовое окружение") {
            for (service in listOf("store", "warehouse")) {
                assertEquals("e2e_gates", Database.scalar(service, "SELECT to_regclass('e2e_gates')::text"), "Explicit e2e profile: $service")
            }
            resources.preserveCache()
        }
        body(resources)
    }
}
