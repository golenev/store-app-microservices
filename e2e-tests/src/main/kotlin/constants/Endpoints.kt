package constants

/** Public endpoints are supplied by the isolated E2E launcher; no global auth/cart/order routes remain. */
object Endpoints {
    val STORE = System.getenv("E2E_STORE_URL") ?: "http://localhost:18889"
    val WAREHOUSE = System.getenv("E2E_WAREHOUSE_URL") ?: "http://localhost:18891"
    val TARIFFS = System.getenv("E2E_TARIFFS_URL") ?: "http://localhost:18890"
    val KAFKA = System.getenv("E2E_KAFKA") ?: "localhost:19093"
}
