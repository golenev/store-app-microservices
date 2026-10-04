package org.golenev.commondto

/** Неизменяемые данные поставки. Повтор сохраняет исходный deliveryId и все позиции. */
data class DeliveryPayload(val deliveryId: String, val items: List<DeliveryLine>)
