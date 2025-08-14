package config

import com.fasterxml.jackson.databind.ObjectMapper
import constants.Endpoints
import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.config.RestAssuredConfig as RAConfig
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.specification.RequestSpecification
import io.qameta.allure.restassured.AllureRestAssured

object RestAssuredConfig {
    init {
        RestAssured.baseURI = Endpoints.BASE_URL
        RestAssured.filters(AllureRestAssured(), RequestLoggingFilter(), ResponseLoggingFilter())
        RestAssured.config = RAConfig.config().objectMapperConfig(
            ObjectMapperConfig.objectMapperConfig()
                .jackson2ObjectMapperFactory { _, _ -> ObjectMapper().findAndRegisterModules() }
        )
    }

    fun given(): RequestSpecification = RestAssured.given()
}

