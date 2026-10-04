package stageTests

import awaitState
import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** Delivery scenarios run through actual Kafka consumers, pricing and both databases; no inventory is seeded directly. */
class ProductFlowE2ETest : ShopE2E() {
    /** CASES-01: a real delivery receives a durable sequence/time/rule and becomes a single correctly priced SKU. */
    @Test @AllureId("1") fun realSupplyPreservesReceivingMetadata() {
        val s=Shop.scope(); val event=Shop.delivery(s); val stock=Shop.supply(s,event); val received=Shop.received(event)
        assertEquals("120.00",stock["unitPrice"].asText()); assertEquals(10,stock["availableQuantity"].asInt())
        assertEquals(1,received["deliverySequence"].asInt()); assertNotNull(received["receivedAt"]); assertNotNull(received["postedAt"])
        assertNotNull(received["items"][0]["tariffRuleId"]); assertEquals(1,received["items"][0]["tariffVersion"].asInt()); Shop.audit(s)
    }

    /** CASES-02: simultaneous publication of the same delivery is consumed twice but results in one receiving/outbox/credit. */
    @Test @AllureId("2") fun concurrentSupplierDuplicateCreatesOneReceipt() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.race({ Shop.publish(event) },{ Shop.publish(event) }); Shop.received(event); Shop.stocked(s,event)
        Shop.drained("logistics.deliveries","warehouse-deliveries-v1"); Shop.drained("warehouse.goods-posted","store-goods-v1")
        assertEquals("1",Database.scalar("warehouse","SELECT count(*) FROM warehouse_outbox WHERE store_id=?",s.store)); assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_movements WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-03: replaying the original priced event and a new eventId for its same business delivery never adds quantity again. */
    @Test @AllureId("3") fun goodsDuplicateWithNewEventIdNeverAddsCredit() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.supply(s,event); val goods=Shop.goods(event)
        Shop.kafka("warehouse.goods-posted",s.store,goods.toString())
        val copy=goods.deepCopy<ObjectNode>().put("eventId",UUID.randomUUID().toString()); Shop.kafka("warehouse.goods-posted",s.store,copy.toString())
        Shop.drained("warehouse.goods-posted","store-goods-v1"); assertEquals(10,Shop.stocked(s,event)["availableQuantity"].asInt()); assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_movements WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-04 WAREHOUSE: changed content under an accepted deliveryId is diagnosed and never replaces its original result. */
    @Test @AllureId("4") fun changedSupplierContentCannotOverwriteReceipt() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.supply(s,event)
        val changed=event.copy(eventId=UUID.randomUUID().toString(),payload=event.payload.copy(items=listOf(event.payload.items.single().copy(quantity=99))))
        Shop.publish(changed); Shop.drained("logistics.deliveries","warehouse-deliveries-v1")
        assertEquals(10,Shop.stocked(s,event)["availableQuantity"].asInt()); assertEquals(10,Shop.received(event)["items"][0]["quantity"].asInt()); Shop.audit(s)
    }

    /** CASES-04 STORE: a priced duplicate with changed quantity is rejected while the prior inventory and price remain intact. */
    @Test @AllureId("41") fun changedGoodsCannotOverwriteInventory() {
        val s=Shop.scope(); val event=Shop.delivery(s); val original=Shop.supply(s,event); val changed=Shop.goods(event).deepCopy<ObjectNode>()
        changed.put("eventId",UUID.randomUUID().toString()); (changed["payload"]["items"][0] as ObjectNode).put("quantity",99)
        Shop.kafka("warehouse.goods-posted",s.store,changed.toString()); Shop.drained("warehouse.goods-posted","store-goods-v1")
        assertEquals(original,Shop.stock(s,event.payload.items.single().productId)); assertEquals("1",Database.scalar("store","SELECT count(*) FROM incoming_goods_diagnostics WHERE raw_message=?",changed.toString())); Shop.audit(s)
    }

    /** CASES-05: malformed text is persisted with Kafka coordinates before its consumer offset advances. */
    @Test @AllureId("5") fun malformedDeliveryHasDurableDiagnostic() {
        val s=Shop.scope(); val raw="{broken-${UUID.randomUUID()}"; Shop.kafka("logistics.deliveries",s.store,raw); Shop.drained("logistics.deliveries","warehouse-deliveries-v1")
        assertEquals("1",Database.scalar("warehouse","SELECT count(*) FROM delivery_diagnostics WHERE raw_message=?",raw)); assertTrue(Shop.catalog(s)["items"].isEmpty); Shop.audit(s)
    }

    /** CASES-05: unknown envelope version becomes an ingress diagnostic without pricing/credit. */
    @Test @AllureId("51") fun unknownVersionHasNoReceipt() {
        val s=Shop.scope(); val event=Shop.delivery(s).copy(schemaVersion=42); val raw=HttpClient.mapper.writeValueAsString(event)
        Shop.kafka("logistics.deliveries",s.store,raw); Shop.drained("logistics.deliveries","warehouse-deliveries-v1")
        assertEquals("1",Database.scalar("warehouse","SELECT count(*) FROM delivery_diagnostics WHERE raw_message=?",raw)); assertTrue(Shop.catalog(s)["items"].isEmpty)
    }

    /** CASES-05: parseable zero quantity is saved as REJECTED rather than retried or credited. */
    @Test @AllureId("52") fun invalidQuantityIsRejected() {
        val s=Shop.scope(); val event=Shop.delivery(s,quantity=0); Shop.kafka("logistics.deliveries",s.store,HttpClient.mapper.writeValueAsString(event))
        val rejected=Shop.received(event,"REJECTED"); assertNotNull(rejected["rejectedPayload"]); assertNotNull(rejected["lastError"]); assertTrue(Shop.catalog(s)["items"].isEmpty)
    }

    /** CASES-32: two lines of the same product are rejected without an ambiguous price or stock movement. */
    @Test @AllureId("32") fun duplicateProductLinesAreRejected() {
        val s=Shop.scope(); val base=Shop.delivery(s); val event=base.copy(payload=base.payload.copy(items=listOf(base.payload.items.single(),base.payload.items.single().copy(lineId="L-2"))))
        Shop.kafka("logistics.deliveries",s.store,HttpClient.mapper.writeValueAsString(event)); Shop.received(event,"REJECTED")
        assertEquals("0",Database.scalar("warehouse","SELECT count(*) FROM warehouse_outbox WHERE store_id=?",s.store)); assertTrue(Shop.catalog(s)["items"].isEmpty)
    }

    /** CASES-08/28: a later supply updates one stable SKU and open cart price; future supply cannot change an accepted snapshot. */
    @Test @AllureId("28") fun replenishmentRepricesStockAndFreezesAcceptedSnapshot() {
        val s=Shop.scope(); val first=Shop.delivery(s,quantity=6); val stock=Shop.supply(s,first); val cart=Shop.filled(s,stock,3)
        val second=Shop.delivery(s,product=first.payload.items.single().productId,quantity=10,price="120.00"); Shop.publish(second); Shop.received(second); val combined=Shop.stocked(s,second,16)
        assertEquals(stock["stockItemId"],combined["stockItemId"]); assertEquals("144.00",combined["unitPrice"].asText()); assertEquals("432.00",Shop.getCart(s,cart)["totalAmount"].asText())
        val accepted=Shop.submit(s,cart); assertEquals(202,accepted.status); val snapshot=Shop.getCart(s,cart)
        val third=Shop.delivery(s,product=first.payload.items.single().productId,quantity=1,price="200.00"); Shop.publish(third); Shop.received(third); Shop.stocked(s,third,14)
        assertEquals(snapshot,Shop.getCart(s,cart)); Shop.audit(s)
    }

    /** CASES-09: the first receiving is gated while a newer delivery posts; releasing it adds quantity without reverting the price. */
    @Test @AllureId("9") fun delayedOlderPricingCannotRevertPrice() {
        val s=Shop.scope(); val older=Shop.delivery(s,quantity=6); Shop.gate("warehouse",s,"BEFORE_PRICING",older.payload.deliveryId)
        try {
            Shop.publish(older); Shop.reached("warehouse",s,"BEFORE_PRICING")
            val newer=Shop.delivery(s,product=older.payload.items.single().productId,quantity=10,price="120.00"); Shop.publish(newer); Shop.received(newer); Shop.stocked(s,newer,10)
            Shop.release("warehouse",s); Shop.received(older); val stock=Shop.stocked(s,older,16)
            assertEquals("144.00",stock["unitPrice"].asText()); assertEquals(1,Shop.received(older)["deliverySequence"].asInt()); assertEquals(2,Shop.received(newer)["deliverySequence"].asInt()); Shop.audit(s)
        } finally { Shop.release("warehouse",s) }
    }

    /** CASES-34: retry-pricing during an active gated claim preserves its fence and ends with one posted event/credit. */
    @Test @AllureId("34") fun manualRetryCannotDuplicateActivePricing() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.gate("warehouse",s,"BEFORE_PRICING",event.payload.deliveryId)
        try {
            Shop.publish(event); Shop.reached("warehouse",s,"BEFORE_PRICING"); val sequence=Shop.received(event,"WAITING_PRICING")["deliverySequence"]
            assertEquals(202,HttpClient.request(Endpoints.WAREHOUSE,"/stores/${s.store}/deliveries/${event.payload.deliveryId}/retry-pricing","POST").status)
            Shop.release("warehouse",s); assertEquals(sequence,Shop.received(event)["deliverySequence"]); Shop.stocked(s,event)
            assertEquals("1",Database.scalar("warehouse","SELECT count(*) FROM warehouse_outbox WHERE store_id=?",s.store)); Shop.audit(s)
        } finally { Shop.release("warehouse",s) }
    }
}
