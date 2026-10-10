package com.shop.warehouse.model;

import com.shop.warehouse.dto.Failure;
import com.shop.warehouse.messaging.dto.InputLine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Разобранный вход с отпечатком и возможной причиной REJECTED; невалидные строки не подменяются валидными. */
public record Accepted(UUID eventId, String storeId, String deliveryId, JsonNode payload,
                           List<InputLine> items, String fingerprint, Failure rejection) { }
