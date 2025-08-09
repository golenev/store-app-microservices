package config;

import com.fasterxml.jackson.databind.ObjectMapper;
import constants.Endpoints;
import io.restassured.RestAssured;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.specification.RequestSpecification;

public final class RestClient {
    static {
        RestAssured.baseURI = Endpoints.BASE_URL;
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        RestAssured.config = RestAssuredConfig.config().objectMapperConfig(
                ObjectMapperConfig.objectMapperConfig()
                        .jackson2ObjectMapperFactory((cls, charset) -> new ObjectMapper().findAndRegisterModules())
        );
    }

    private RestClient() {
    }

    public static RequestSpecification given() {
        return RestAssured.given();
    }
}
