package config

import com.fasterxml.jackson.databind.ObjectMapper
import constants.Endpoints
import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.specification.RequestSpecification

object RestClient {
    init {
        RestAssured.baseURI = Endpoints.BASE_URL
        RestAssured.filters(RequestLoggingFilter(), ResponseLoggingFilter())
        RestAssured.config = RestAssuredConfig.config().objectMapperConfig(
            ObjectMapperConfig.objectMapperConfig()
                .jackson2ObjectMapperFactory { _, _ -> ObjectMapper().findAndRegisterModules() }
        )
    }

    fun given(): RequestSpecification = RestAssured.given()
}

