package stageTests

import awaitState
import config.Database
import config.HttpClient
import constants.Endpoints
import helpers.*
import io.qameta.allure.AllureId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

/** Global cache/outage actions run serially in their dedicated environment; each rule/city fixture has a unique namespace. */
class TariffApiE2ETest : ShopE2E() {
    /** Creates an explicit rule payload; boundaries/rate remain decimal strings and no legacy percentage API is used. */
    private fun rule(city: String, rate: String, lower: String="0.00", upper: String?=null): Map<String,Any?> =
        mapOf("productType" to "NON_FOOD","cityId" to city,"currency" to "RUB","lowerBound" to lower,"upperBound" to upper,"markupRate" to rate)

    /** Reads the actual HTTP quote for a chosen fixture/range; errors remain visible through status/code. */
    private fun quote(city: String, price: String="100.00") = HttpClient.request(Endpoints.TARIFFS,"/tariffs/quote?productType=NON_FOOD&purchasePrice=$price&currency=RUB&cityId=$city")

    /** CASES-10/11: cached quote survives CRUD until reset; reset leaves inventory unchanged and only a later delivery reprices it. */
    @Test @AllureId("10") fun cacheSnapshotRequiresResetAndNewSupply() {
        val city="C-${UUID.randomUUID()}"; val s=Shop.scope(city)
        val created=HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules","POST",rule(city,"0.20")); assertEquals(201,created.status); val id=created.json["tariffRuleId"].asText()
        assertEquals("0.20",quote(city).json["markupRate"].asText()); val first=Shop.delivery(s); val stock=Shop.supply(s,first)
        val updated=HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules/$id","PUT",rule(city,"0.30")); assertEquals(200,updated.status)
        assertEquals("0.20",quote(city).json["markupRate"].asText()); assertEquals(1,quote(city).json["tariffVersion"].asInt())
        assertEquals(200,HttpClient.request(Endpoints.TARIFFS,"/tariffs/cache/reset","POST").status)
        assertEquals("0.30",quote(city).json["markupRate"].asText()); assertEquals(2,quote(city).json["tariffVersion"].asInt())
        assertEquals("120.00",Shop.stock(s,first.payload.items.single().productId)!!["unitPrice"].asText())
        val second=Shop.delivery(s,product=first.payload.items.single().productId,quantity=1); Shop.publish(second); Shop.received(second)
        assertEquals("130.00",Shop.stocked(s,second,11)["unitPrice"].asText()); assertEquals(stock["stockItemId"],Shop.stocked(s,second,11)["stockItemId"]); Shop.audit(s)
        assertEquals(204,HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules/$id","DELETE").status)
    }

    /** CASES-12: each lower/upper neighbour independently selects the expected [lower,upper) Moscow rate over HTTP. */
    @ParameterizedTest @AllureId("12") @CsvSource("0.01,0.20", "499.99,0.20", "500.00,0.25", "999.99,0.25", "1000.00,0.30", "1000.01,0.30")
    fun tariffBoundarySelectsOneRule(price: String, rate: String) { val reply=quote("MOSCOW",price); assertEquals(200,reply.status); assertEquals(rate,reply.json["markupRate"].asText()) }

    /** CASES-12: an absent city has a named missing-rule error rather than a fabricated zero markup. */
    @Test @AllureId("121") fun absentRuleIsAnError() { val reply=quote("C-${UUID.randomUUID()}"); assertEquals(404,reply.status); assertEquals("TARIFF_NOT_FOUND",reply.json["code"].asText()) }

    /** CASES-12: overlapping rules created through CRUD produce an ambiguous quote, not arbitrary first-match pricing. */
    @Test @AllureId("122") fun ambiguousRulesAreAnError() {
        val city="C-${UUID.randomUUID()}"
        assertEquals(201,HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules","POST",rule(city,"0.20")).status)
        assertEquals(201,HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules","POST",rule(city,"0.30")).status)
        val reply=quote(city); assertEquals(409,reply.status); assertEquals("TARIFF_AMBIGUOUS",reply.json["code"].asText())
    }

    /** CASES-12: real FOOD pricing rounds 0.505 HALF_UP to 0.51 before STORE consumes the result. */
    @Test @AllureId("123") fun fractionalPricingRoundsHalfUp() {
        val s=Shop.scope(); val event=Shop.delivery(s,price="0.50",type="FOOD"); assertEquals("0.51",Shop.supply(s,event)["unitPrice"].asText()); Shop.audit(s)
    }

    /** CASES-13: Redis outage permits genuine database quote/pricing fallback; it is restored before any subsequent cache test. */
    @Test @AllureId("13") fun redisOutageAllowsDatabasePricingFallback() {
        val s=Shop.scope(); val event=Shop.delivery(s,price="123.45"); Shop.control("stop","redis")
        try { assertEquals(200,quote("MOSCOW","123.45").status); assertEquals("148.14",Shop.supply(s,event)["unitPrice"].asText()); Shop.audit(s) }
        finally { Shop.control("start","redis"); Shop.healthy(Endpoints.TARIFFS) }
    }

    /** CASES-33: pending missing-rule pricing resumes automatically after creating a correct rule and resetting the cache. */
    @Test @AllureId("33") fun newRuleAutomaticallyUnblocksWaitingDelivery() {
        val city="C-${UUID.randomUUID()}"; val s=Shop.scope(city); val event=Shop.delivery(s); Shop.publish(event)
        awaitState("missing rule persisted",read={ Shop.receiving(event) },ready={ it.status==200 && it.json.path("lastError").isObject })
        assertTrue(Shop.catalog(s)["items"].isEmpty)
        val created=HttpClient.request(Endpoints.TARIFFS,"/tariffs/rules","POST",rule(city,"0.20")); assertEquals(201,created.status)
        assertEquals(200,HttpClient.request(Endpoints.TARIFFS,"/tariffs/cache/reset","POST").status)
        val posted=Shop.received(event); assertEquals(created.json["tariffRuleId"],posted["items"][0]["tariffRuleId"]); assertEquals("120.00",Shop.stocked(s,event)["unitPrice"].asText()); Shop.audit(s)
    }
}
