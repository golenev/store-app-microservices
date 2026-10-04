package stageTests

import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.qameta.allure.AllureId
import java.util.UUID

/** Accepted expenses are exercised only after real supplier ingress and asynchronous inventory consumption. */
class OrderE2ETest : ShopE2E() {
    /** CASES-16: given ten received units, acceptance deducts three, closes the cart and persists one immutable Kafka operation. */
    @Test @AllureId("16") fun acceptancePersistsExactSnapshotAndEvent() {
        val s = Shop.scope(); val stock = Shop.supply(s); val cart = Shop.filled(s, stock, 3)
        val accepted = Shop.submit(s, cart); assertEquals(202, accepted.status); Shop.published(s, accepted.json)
        assertEquals(7, Shop.stock(s, stock["productId"].asText())!!["availableQuantity"].asInt())
        val closed = Shop.getCart(s, cart); assertEquals("SUBMITTED", closed["state"].asText()); assertEquals("360.00", closed["totalAmount"].asText())
        val events = Shop.events("store.order-submitted", accepted.json["eventId"].asText(), 1)
        assertEquals("OrderSubmitted", events.single()["eventType"].asText()); assertEquals("360.00", events.single()["payload"]["totalAmount"].asText())
        assertEquals("1", Database.scalar("store", "SELECT count(*) FROM stock_expenses WHERE store_id=?", s.store)); Shop.audit(s)
    }

    /** CASES-14: two independent carts may each hold all five units; replacing one with six fails without changing inventory/version. */
    @Test @AllureId("14") fun independentCartsNeverReserve() {
        val s = Shop.scope(); val stock = Shop.supply(s, Shop.delivery(s, quantity=5)); val a = Shop.filled(s, stock, 5); val b = Shop.filled(s, stock, 5)
        val refused = Shop.put(s, a, stock, 6); assertEquals(409, refused.status); assertEquals("INSUFFICIENT_STOCK", refused.json["code"].asText())
        assertEquals(a, Shop.getCart(s,a)); assertEquals(b, Shop.getCart(s,b)); assertEquals(5, Shop.stock(s,stock["productId"].asText())!!["availableQuantity"].asInt()); Shop.audit(s)
    }

    /** CASES-15: two PUTs starting from one version have one winner and no silently overwritten cart composition. */
    @Test @AllureId("15") fun competingCartEditsHaveOneVersionWinner() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.cart(s)
        val replies=Shop.race({ Shop.put(s,cart,stock,2) }, { Shop.put(s,cart,stock,3) })
        assertEquals(listOf(200,409), replies.map { it.status }.sorted()); val actual=Shop.getCart(s,cart)
        assertEquals(1,actual["version"].asInt()); assertEquals(replies.single { it.status==200 }.json,actual); Shop.audit(s)
    }

    /** CASES-17: another buyer depletes one of two lines; checkout rolls back all lines and preserves the open cart. */
    @Test @AllureId("17") fun insufficientOneLineNeverPartiallyDeducts() {
        val s=Shop.scope(); val first=Shop.supply(s); val second=Shop.supply(s)
        val cart=Shop.put(s,Shop.filled(s,first,3),second,3).json
        assertEquals(202,Shop.submit(s,Shop.filled(s,second,10)).status)
        val response=Shop.submit(s,cart); assertEquals(409,response.status); assertEquals("INSUFFICIENT_STOCK",response.json["code"].asText())
        assertEquals(10,Shop.stock(s,first["productId"].asText())!!["availableQuantity"].asInt()); assertEquals(cart,Shop.getCart(s,cart))
        assertEquals("1",Database.scalar("store","SELECT count(*) FROM submissions WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-18: accepted HTTP request repeated sequentially returns one operation and never charges twice. */
    @Test @AllureId("18") fun acceptedRequestReplaysOriginalVersion() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); val key=UUID.randomUUID().toString()
        val accepted=Shop.submit(s,cart,key); assertEquals(202,accepted.status); val repeated=Shop.submit(s,cart,key)
        assertEquals(202,repeated.status); assertEquals(accepted.json["submissionId"],repeated.json["submissionId"])
        assertEquals(7,Shop.stock(s,stock["productId"].asText())!!["availableQuantity"].asInt()); Shop.audit(s)
    }

    /** CASES-18: simultaneous identical submit requests resolve real UNIQUE/transaction races to one accepted operation. */
    @Test @AllureId("181") fun simultaneousSameKeyCreatesOneExpense() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); val key=UUID.randomUUID().toString()
        val replies=Shop.race({ Shop.submit(s,cart,key) }, { Shop.submit(s,cart,key) })
        assertEquals(listOf(202,202),replies.map { it.status }); assertEquals(replies[0].json["submissionId"],replies[1].json["submissionId"])
        assertEquals("1",Database.scalar("store","SELECT count(*) FROM stock_expenses WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-19: changing either cart or version under an already accepted key is a conflict, with no additional expense. */
    @Test @AllureId("19") fun acceptedKeyCannotChangeRequest() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,1); val key=UUID.randomUUID().toString(); assertEquals(202,Shop.submit(s,cart,key).status)
        val other=Shop.filled(s,stock,1)
        for (request in listOf(other,Shop.getCart(s,cart))) { val response=Shop.submit(s,request,key); assertEquals(409,response.status); assertEquals("IDEMPOTENCY_KEY_REUSED",response.json["code"].asText()) }
        assertEquals("1",Database.scalar("store","SELECT count(*) FROM submissions WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-20: closed carts reject new keys and edits while preserving their immutable accepted snapshot. */
    @Test @AllureId("20") fun closedCartRejectsNewExpenseAndMutation() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,1); assertEquals(202,Shop.submit(s,cart).status)
        val closed=Shop.getCart(s,cart); assertEquals(409,Shop.submit(s,closed).status); assertEquals(409,Shop.put(s,closed,stock,2).status)
        assertEquals(closed,Shop.getCart(s,closed)); Shop.audit(s)
    }

    /** CASES-21: synchronized last-unit purchases produce one winner, one insufficiency and exactly one debit. */
    @Test @AllureId("21") fun lastUnitHasOneWinner() {
        val s=Shop.scope(); val stock=Shop.supply(s,Shop.delivery(s,quantity=1)); val a=Shop.filled(s,stock,1); val b=Shop.filled(s,stock,1)
        val replies=Shop.race({ Shop.submit(s,a) }, { Shop.submit(s,b) }); assertEquals(listOf(202,409),replies.map { it.status }.sorted())
        assertEquals("INSUFFICIENT_STOCK",replies.single { it.status==409 }.json["code"].asText()); assertEquals(0,Shop.stock(s,stock["productId"].asText())!!["availableQuantity"].asInt()); Shop.audit(s)
    }

    /** CASES-22: stale expectedCartVersion rejects acceptance without changing stock or creating an outbox. */
    @Test @AllureId("22") fun staleVersionCannotPurchase() {
        val s=Shop.scope(); val stock=Shop.supply(s); val old=Shop.filled(s,stock,1); val current=Shop.put(s,old,stock,2).json
        val rejected=Shop.submit(s,old); assertEquals(409,rejected.status); assertEquals("CART_VERSION_CONFLICT",rejected.json["code"].asText())
        assertEquals(current,Shop.getCart(s,old)); assertEquals("0",Database.scalar("store","SELECT count(*) FROM store_outbox WHERE store_id=?",s.store)); Shop.audit(s)
    }

    /** CASES-23/30: identical keys in separate scopes create independent expenses; foreign cart/stock/submission lookups fail. */
    @Test @AllureId("30") fun storeScopesSeparateKeysAndResources() {
        val a=Shop.scope(); val b=Shop.scope("SPB"); val sa=Shop.supply(a); val sb=Shop.supply(b); val ca=Shop.filled(a,sa,1); val cb=Shop.filled(b,sb,1); val key=UUID.randomUUID().toString()
        val aa=Shop.submit(a,ca,key); val ab=Shop.submit(b,cb,key); assertEquals(202,aa.status); assertEquals(202,ab.status); assertNotEquals(aa.json["submissionId"],ab.json["submissionId"])
        assertEquals(404,HttpClient.request(Endpoints.STORE,"/stores/${b.store}/carts/${ca["cartId"].asText()}").status)
        assertEquals(404,Shop.put(b,Shop.cart(b),sa,1).status)
        assertEquals(404,HttpClient.request(Endpoints.STORE,"/stores/${b.store}/submissions/${aa.json["submissionId"].asText()}").status)
        assertEquals("121.00",sb["unitPrice"].asText()); Shop.audit(a); Shop.audit(b)
    }

    /** CASES-24: scoped SQL fault after inventory update rolls back acceptance; the original key works once after fault release. */
    @Test @AllureId("24") fun commitFailureRollsBackWholeAcceptance() {
        val s=Shop.scope(); val stock=Shop.supply(s); val cart=Shop.filled(s,stock,3); val key=UUID.randomUUID().toString()
        Database.update("store", "CREATE OR REPLACE FUNCTION e2e_reject_expense() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM e2e_gates WHERE store_id=NEW.store_id AND point='SUBMIT_ROLLBACK' AND blocked) THEN RAISE EXCEPTION 'e2e controlled rollback'; END IF; RETURN NEW; END $$")
        Database.update("store", "DROP TRIGGER IF EXISTS e2e_expense_fail ON stock_expenses")
        Database.update("store", "CREATE TRIGGER e2e_expense_fail BEFORE INSERT ON stock_expenses FOR EACH ROW EXECUTE FUNCTION e2e_reject_expense()")
        Shop.gate("store",s,"SUBMIT_ROLLBACK")
        try {
            val rejected=Shop.submit(s,cart,key); assertEquals(503,rejected.status); assertEquals("DEPENDENCY_UNAVAILABLE",rejected.json["code"].asText()); assertEquals(cart,Shop.getCart(s,cart))
            assertEquals("0",Database.scalar("store","SELECT count(*) FROM submissions WHERE store_id=?",s.store)); assertEquals("0",Database.scalar("store","SELECT count(*) FROM store_outbox WHERE store_id=?",s.store)); Shop.audit(s)
        } finally { Shop.release("store",s) }
        assertEquals(202,Shop.submit(s,cart,key).status); Shop.audit(s)
    }
}
