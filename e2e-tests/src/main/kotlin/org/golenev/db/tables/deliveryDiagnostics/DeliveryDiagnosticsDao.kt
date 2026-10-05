package org.golenev.db.tables.deliveryDiagnostics

import org.golenev.db.dbWarehouseExec
import org.jetbrains.exposed.sql.selectAll

/** Доступ к строкам DeliveryDiagnosticsTable; подсчёты и проверки выполняет вызывающий тест. */
object DeliveryDiagnosticsDao {
    /** Читает диагностики с точным исходным текстом raw. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByRawMessage(raw: String): List<DeliveryDiagnosticsRow> {
        return dbWarehouseExec {
            DeliveryDiagnosticsTable.selectAll().where { DeliveryDiagnosticsTable.rawMessage eq raw }.map {
                DeliveryDiagnosticsRow(
                    rawMessage = it[DeliveryDiagnosticsTable.rawMessage]
                )
            }
        }
    }

    /** Читает диагностики, содержащие eventId в исходном тексте; поиск сохраняет прежний SQL-шаблон. Чтение выполняется в отдельной транзакции; отсутствие строк возвращает пустой список. */
    fun findByEventId(eventId: String): List<DeliveryDiagnosticsRow> {
        return dbWarehouseExec {
            DeliveryDiagnosticsTable.selectAll().where { DeliveryDiagnosticsTable.rawMessage like "%$eventId%" }.map {
                DeliveryDiagnosticsRow(
                    rawMessage = it[DeliveryDiagnosticsTable.rawMessage]
                )
            }
        }
    }
}
