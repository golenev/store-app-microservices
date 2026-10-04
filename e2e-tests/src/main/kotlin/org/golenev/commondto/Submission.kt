package org.golenev.commondto

/** Сохранённая операция оформления. Пока публикация не подтверждена, publishedAt отсутствует. */
data class Submission(
    /**
     * Идентификатор магазина, к которому относятся данные.
     */
    val storeId: String,
    /**
     * Идентификатор принятой операции оформления корзины.
     */
    val submissionId: String,
    /**
     * Идентификатор корзины внутри магазина.
     */
    val cartId: String,
    /**
     * Идентификатор события для распознавания повторной доставки сообщения.
     */
    val eventId: String,
    /**
     * Состояние публикации события оформления в Kafka.
     */
    val publicationStatus: PublicationStatus,
    /**
     * Время принятия операции оформления и фиксации списания товара.
     */
    val acceptedAt: String,
    /**
     * Время подтверждения публикации события в Kafka; null до подтверждения.
     */
    val publishedAt: String? = null
)
