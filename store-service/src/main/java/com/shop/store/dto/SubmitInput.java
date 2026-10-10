package com.shop.store.dto;

/** Запрос оформления с ожидаемой версией; цены и состав определяет сервер. */
public record SubmitInput(long expectedCartVersion) { }
