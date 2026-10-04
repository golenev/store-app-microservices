package stageTests

import awaitState
import config.Database
import helpers.*
import constants.Endpoints
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.qameta.allure.AllureId
import java.util.UUID

/** Failure/restart windows are controlled only inside the validated owned E2E project and restored in finally blocks. */
class KafkaTariffTest : ShopE2E() {
    /** CASES-06: tariffs outage persists a failed pricing attempt; restoring the real service recovers automatically without manual retry. */
    @Test @AllureId("6") fun pricingRecoversTariffsOutageAutomatically() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.control("stop","tariffs-service")
        try {
            Shop.publish(event); awaitState("failed pricing persisted",read={ Shop.receiving(event) },ready={ it.status==200 && it.json.path("lastError").isObject })
            assertTrue(Shop.catalog(s)["items"].isEmpty)
        } finally { Shop.control("start","tariffs-service"); Shop.healthy(Endpoints.TARIFFS) }
        Shop.received(event); Shop.stocked(s,event); Shop.audit(s)
    }

    /** CASES-07: restarting an application with a claimed WAITING_PRICING recovers its original sequence/time and single credit. */
    @Test @AllureId("7") fun restartRecoversWaitingPricingWithoutChangingIdentity() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.gate("warehouse",s,"BEFORE_PRICING",event.payload.deliveryId)
        try {
            Shop.publish(event); Shop.reached("warehouse",s,"BEFORE_PRICING"); val waiting=Shop.received(event,"WAITING_PRICING")
            Shop.control("stop","warehouse-service"); Shop.release("warehouse",s); Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE)
            val posted=Shop.received(event); assertEquals(waiting["receivedAt"],posted["receivedAt"]); assertEquals(waiting["deliverySequence"],posted["deliverySequence"])
            Shop.stocked(s,event); Shop.audit(s)
        } finally { Shop.release("warehouse",s); Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE) }
    }

    /** CASES-25 WAREHOUSE: after POSTED the paused broker leaves persisted failed PENDING; recovery sends the original calculated event. */
    @Test @AllureId("25") fun warehouseOutboxRecoversBrokerOutage() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.gate("warehouse",s,"BEFORE_PUBLISH")
        var paused=false
        try {
            Shop.publish(event); val posted=Shop.received(event); Shop.reached("warehouse",s,"BEFORE_PUBLISH"); val payload=Shop.goods(event)
            Shop.control("pause","kafka"); paused=true; Shop.release("warehouse",s)
            awaitState("warehouse failed publication",read={ Database.scalar("warehouse","SELECT last_error FROM warehouse_outbox WHERE store_id=?",s.store) },ready={ it!=null })
            assertEquals("PENDING",Database.scalar("warehouse","SELECT publication_status FROM warehouse_outbox WHERE store_id=?",s.store))
            Shop.control("unpause","kafka"); paused=false; Shop.stocked(s,event)
            assertEquals(posted["postedAt"],Shop.received(event)["postedAt"]); assertEquals(payload,Shop.goods(event)); Shop.audit(s)
        } finally { Shop.release("warehouse",s); if(paused) Shop.control("unpause","kafka") }
    }

    /** CASES-25 STORE: submit commits while Kafka is paused; recovery publishes PENDING without another stock expense. */
    @Test @AllureId("251") fun acceptedExpenseRecoversBrokerOutage() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); Shop.control("pause","kafka")
        val accepted: config.Reply
        try {
            accepted=Shop.submit(s,cart); assertEquals(202,accepted.status)
            awaitState("store failed publication",read={ Database.scalar("store","SELECT last_error FROM store_outbox WHERE store_id=?",s.store) },ready={ it!=null })
            assertEquals(7,Shop.stock(s,stock["productId"].asText())!!["availableQuantity"].asInt())
        } finally { Shop.control("unpause","kafka") }
        Shop.published(s,accepted.json); assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_expenses WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-26 WAREHOUSE: crash after POSTED commit and claimed outbox but before send recovers one immutable event and credit. */
    @Test @AllureId("26") fun warehouseRestartAfterCommitBeforePublication() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.gate("warehouse",s,"BEFORE_PUBLISH")
        try {
            Shop.publish(event); val posted=Shop.received(event); Shop.reached("warehouse",s,"BEFORE_PUBLISH"); val payload=Shop.goods(event)
            assertTrue(Shop.catalog(s)["items"].isEmpty); Shop.control("stop","warehouse-service"); Shop.release("warehouse",s)
            Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE); Shop.stocked(s,event)
            assertEquals(posted,Shop.received(event)); assertEquals(payload,Shop.goods(event)); Shop.audit(s)
        } finally { Shop.release("warehouse",s); Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE) }
    }

    /** CASES-26 STORE: committed expense with a claimed unsent outbox survives restart and still replays its original HTTP key/version. */
    @Test @AllureId("261") fun storeRestartAfterCommitBeforePublication() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); val key=UUID.randomUUID().toString(); Shop.gate("store",s,"BEFORE_PUBLISH")
        try {
            val accepted=Shop.submit(s,cart,key); assertEquals(202,accepted.status); Shop.reached("store",s,"BEFORE_PUBLISH"); val snapshot=Shop.getCart(s,cart)
            Shop.control("stop","store-service"); Shop.release("store",s); Shop.control("start","store-service"); Shop.healthy(Endpoints.STORE); Shop.published(s,accepted.json)
            assertEquals(snapshot,Shop.getCart(s,cart)); assertEquals(accepted.json["submissionId"],Shop.submit(s,cart,key).json["submissionId"]); Shop.audit(s)
        } finally { Shop.release("store",s); Shop.control("start","store-service"); Shop.healthy(Endpoints.STORE) }
    }

    /** CASES-27 WAREHOUSE: Kafka ack precedes a gated PUBLISHED mark; restart yields identical physical copies and only one logical credit. */
    @Test @AllureId("27") fun warehouseAckLossReplaysExactGoods() {
        val s=Shop.scope(); val event=Shop.delivery(s); Shop.gate("warehouse",s,"AFTER_ACK")
        try {
            Shop.publish(event); Shop.received(event); val id=Shop.reached("warehouse",s,"AFTER_ACK"); Shop.stocked(s,event)
            Shop.control("stop","warehouse-service"); Shop.release("warehouse",s); Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE)
            val copies=Shop.events("warehouse.goods-posted",id,2); assertTrue(copies.all { it==copies.first() })
            Shop.drained("warehouse.goods-posted","store-goods-v1"); assertEquals(10,Shop.stocked(s,event)["availableQuantity"].asInt()); assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_movements WHERE store_id=?",s.store)); Shop.audit(s)
        } finally { Shop.release("warehouse",s); Shop.control("start","warehouse-service"); Shop.healthy(Endpoints.WAREHOUSE) }
    }

    /** CASES-27 STORE: ack loss/restart replays the saved OrderSubmitted with the same ID/body and no second debit. */
    @Test @AllureId("271") fun storeAckLossReplaysExactOrder() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); Shop.gate("store",s,"AFTER_ACK")
        try {
            val accepted=Shop.submit(s,cart); assertEquals(202,accepted.status); val id=Shop.reached("store",s,"AFTER_ACK")
            assertEquals("PENDING",Database.scalar("store","SELECT publication_status FROM store_outbox WHERE store_id=?",s.store))
            Shop.control("stop","store-service"); Shop.release("store",s); Shop.control("start","store-service"); Shop.healthy(Endpoints.STORE)
            val copies=Shop.events("store.order-submitted",id,2); assertTrue(copies.all { it==copies.first() }); Shop.published(s,accepted.json)
            assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_expenses WHERE store_id=?",s.store)); Shop.audit(s)
        } finally { Shop.release("store",s); Shop.control("start","store-service"); Shop.healthy(Endpoints.STORE) }
    }
}
