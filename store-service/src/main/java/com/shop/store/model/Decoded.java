package com.shop.store.model;

import com.shop.store.messaging.dto.GoodsEvent;

/**
 * Проверенное событие поставки и контрольная сумма его содержимого. Идентификатор транспортного события в
 * сумму не входит, поэтому повтор поставки можно сравнить независимо от него.
 *
 * @param event событие оприходованной поставки
 * @param fingerprint контрольная сумма содержимого поставки для сравнения повторов
 */
public record Decoded(GoodsEvent event, String fingerprint) { }
