package com.tariffs.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** Настраивает строгий разбор JSON и общие UTC-часы HTTP-контракта тарифов. */
@Configuration
public class TariffApiConfig {
    /**
     * Возвращает production-часы UTC; тест может заменить их фиксированным временем.
     */
    @Bean
    public Clock tariffClock() { return Clock.systemUTC(); }

    /**
     * Возвращает настройку JSON, отклоняющую неизвестные и повторные поля, отсутствующие параметры records и
     * преобразования числа/boolean в строку.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictTariffJson() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
