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

/**
 * Задаёт часы и правила чтения JSON для HTTP API тарифов.
 */
@Configuration
public class TariffApiConfig {
    /**
     * Возвращает системные часы UTC для времени ошибок и сброса кеша. В тестах вместо них можно подключить
     * часы с фиксированным временем.
     *
     * @return системные или фиксированные часы, заданные этой конфигурацией
     */
    @Bean
    public Clock tariffClock() { return Clock.systemUTC(); }

    /**
     * Настраивает чтение JSON: неизвестные и повторные поля, отсутствующие поля моделей {@code record}, лишние
     * данные после документа и преобразование чисел или логических значений в строки считаются ошибкой.
     *
     * @return правила строгого чтения входного JSON
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
