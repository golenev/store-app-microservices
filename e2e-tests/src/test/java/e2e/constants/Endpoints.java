package e2e.constants;

public final class Endpoints {
    public static final String BASE_URL = "http://localhost:6789";
    public static final String AUTH = "/api/v1/auth";
    public static final String SEND_TO_KAFKA = "/api/v1/sendToKafka";
    public static final String PRODUCTS = "/api/v1/products";
    public static final String CART = "/api/cart";

    public static final String TARIFFS_BASE_URL = "http://localhost:6790";
    public static final String TARIFFS = "/tariffs";

    private Endpoints() {
    }
}
