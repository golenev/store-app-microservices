package com.shop.warehouse.model;

import com.shop.warehouse.dto.Line;

import java.util.List;
import java.util.UUID;

/** Захваченная попытка расчёта с городом, токеном и исходными строками. */
public record PricingWork(String storeId, String deliveryId, String cityId, UUID token,
                              long attemptCount, List<Line> items) { }
