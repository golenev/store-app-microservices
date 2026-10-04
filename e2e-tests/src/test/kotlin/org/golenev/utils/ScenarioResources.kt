package org.golenev.utils

import org.golenev.db.tables.observation.ObservationDao
import org.golenev.restapi.endpoints.*
import io.lettuce.core.RedisClient
import io.qameta.allure.Allure
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

/** Учёт ресурсов сценария. Действия очистки регистрируются до операций, которые могут завершиться частично. */
class ScenarioResources : AutoCloseable {
    private val cleanup = mutableListOf<Pair<String, () -> Unit>>()
    private var kafkaPaused = false

    /** Регистрирует безопасное повторное восстановление ресурса. При закрытии выполняются все действия, а ошибки собираются вместе. */
    fun onClose(description: String, action: () -> Unit) {
        cleanup += description to action
    }

    /** Сохраняет снимок изолированного тарифного кеша. При очистке возвращает значения и задаёт новое поколение, отсекающее запоздалые записи. */
    fun preserveCache() {
        val client = RedisClient.create("redis://localhost:${System.getenv("E2E_REDIS_PORT") ?: error("E2E_REDIS_PORT required")}")
        onClose("Закрываем клиент Redis") { client.shutdown() }
        val connection = client.connect()
        onClose("Закрываем соединение Redis") { connection.close() }
        val commands = connection.sync()
        val snapshot = commands.hgetall("tariff-quotes:v1:entries").toMap()
        onClose("Восстанавливаем снимок тарифного кеша с новым поколением") {
            commands.set("tariff-quotes:v1:epoch", UUID.randomUUID().toString())
            commands.del("tariff-quotes:v1:entries")
            if (snapshot.isNotEmpty()) commands.hset("tariff-quotes:v1:entries", snapshot)
        }
    }

    /** Выделяет уникальный город до создания правил. Существующие правила тестового окружения не изменяются и не удаляются. */
    fun city(): String {
        val city = "C-${UUID.randomUUID()}"
        onClose("Удаляем свои тарифные правила города $city") { ObservationDao.update("tariffs", "DELETE FROM tariff_rules WHERE city_id=?", city) }
        return city
    }

    /** Регистрирует магазин до его создания в базах. Остатки затем формируются только через настоящую поставку. */
    fun scope(city: String = "MOSCOW"): Scope {
        val store = "S-${UUID.randomUUID()}"
        onClose("Удаляем данные своего магазина $store") { purgeScope(store) }
        ObservationDao.update("warehouse", "INSERT INTO stores(store_id,city) VALUES (?,?)", store, city)
        ObservationDao.update("store", "INSERT INTO store_scopes(store_id) VALUES (?)", store)
        return store to city
    }

    /** Открывает готовый наблюдатель до действия и регистрирует закрытие. При ошибке запуска сам наблюдатель освобождает частично созданный клиент. */
    fun observe(topic: String, key: String): KafkaObservation {
        val observer = KafkaObservation.open(topic, key)
        onClose("Закрываем наблюдатель: топик $topic, ключ $key") { observer.close() }
        return observer
    }

    /** Регистрирует снятие тестовой задержки до её установки. Ошибка подготовки не оставляет обработчик заблокированным. */
    fun gate(service: String, scope: Scope, point: String, subject: String = "*") {
        onClose("Снимаем задержку $point сервиса $service для магазина ${scope.store}") { Shop.release(service, scope) }
        ObservationDao.update(service, "INSERT INTO e2e_gates(store_id,subject_id,point) VALUES (?,?,?)", scope.store, subject, point)
    }

    /** Регистрирует восстановление сервиса до его остановки, в том числе на случай частичного сбоя команды. */
    fun stop(service: String, healthUrl: String? = null) {
        onClose("Восстанавливаем сервис $service") {
            Shop.control("start", service)
            if (healthUrl != null) Shop.healthy(healthUrl)
        }
        Shop.control("stop", service)
    }

    /** Приостанавливает только брокер своего окружения. Восстановление регистрируется до установки сбоя. */
    fun pauseKafka() {
        onClose("Снимаем паузу своего брокера") { resumeKafka() }
        kafkaPaused = true
        Shop.control("pause", "kafka")
    }

    /** Снимает установленную этим сценарием паузу. Явное восстановление и последующая очистка допускают повторный вызов. */
    fun resumeKafka() {
        if (!kafkaPaused) return
        Shop.control("unpause", "kafka")
        kafkaPaused = false
    }

    /** Устанавливает уникальный SQL-триггер сбоя для своего магазина. Триггер и функция удаляются даже после частичной ошибки установки. */
    fun failExpense(scope: Scope) {
        val name = "e2e_expense_${UUID.randomUUID().toString().replace("-", "")}"
        onClose("Удаляем SQL-сбой своего магазина: $name") {
            ObservationDao.update("store", "DROP TRIGGER IF EXISTS $name ON stock_expenses")
            ObservationDao.update("store", "DROP FUNCTION IF EXISTS $name()")
        }
        ObservationDao.update("store", "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.store_id='${scope.store}' AND EXISTS(SELECT 1 FROM e2e_gates WHERE store_id=NEW.store_id AND point='SUBMIT_ROLLBACK' AND blocked) THEN RAISE EXCEPTION 'e2e controlled rollback'; END IF; RETURN NEW; END $$")
        ObservationDao.update("store", "CREATE TRIGGER $name BEFORE INSERT ON stock_expenses FOR EACH ROW EXECUTE FUNCTION $name()")
        gate("store", scope, "SUBMIT_ROLLBACK")
    }

    /** Удаляет только записи своего магазина в порядке внешних ключей. Общие тестовые данные, чужие базы и тома Docker сохраняются. */
    private fun purgeScope(store: String) {
        for (table in listOf("warehouse_outbox", "received_events", "delivery_items", "deliveries", "e2e_gates")) {
            ObservationDao.update("warehouse", "DELETE FROM $table WHERE store_id=?", store)
        }
        ObservationDao.update("warehouse", "DELETE FROM delivery_diagnostics WHERE raw_message LIKE ?", "%$store%")
        ObservationDao.update("warehouse", "DELETE FROM stores WHERE store_id=?", store)
        for (table in listOf("store_outbox", "stock_expenses", "submissions", "cart_items", "carts", "stock_movements", "processed_events", "inventory", "stock_receipts", "e2e_gates")) {
            ObservationDao.update("store", "DELETE FROM $table WHERE store_id=?", store)
        }
        ObservationDao.update("store", "DELETE FROM incoming_goods_diagnostics WHERE raw_message LIKE ?", "%$store%")
        ObservationDao.update("store", "DELETE FROM store_scopes WHERE store_id=?", store)
    }

    /** Выполняет все действия восстановления в обратном порядке. Дополнительные ошибки очистки сохраняются как suppressed, не скрывая первую. */
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
