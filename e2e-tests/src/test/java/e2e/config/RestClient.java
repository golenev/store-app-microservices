package e2e.config;

import e2e.constants.Endpoints;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.specification.RequestSpecification;

public final class RestClient {
    static {
        RestAssured.baseURI = Endpoints.BASE_URL;
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
    }

    private RestClient() {
    }

    public static RequestSpecification given() {
        return RestAssured.given();
    }
}
