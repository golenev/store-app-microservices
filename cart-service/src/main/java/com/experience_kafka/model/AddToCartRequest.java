package com.experience_kafka.model;

import jakarta.validation.constraints.NotNull;

/**
 * Запрос на добавление товара в корзину. Использует штрихкод товара.
 */
public record AddToCartRequest(@NotNull Long barcodeId) {}

