package org.golenev.commondto

/** Неизменяемые данные поставки. Повтор сохраняет исходный deliveryId и все позиции. */
data class DeliveryPayload(
    /**
     * Бизнес-идентификатор поставки; сохраняется при её повторной отправке.
     */
    val deliveryId: String,
    /**
     * Исходные позиции поставки с закупочной ценой и количеством.
     */
    val items: List<DeliveryLine>
)
