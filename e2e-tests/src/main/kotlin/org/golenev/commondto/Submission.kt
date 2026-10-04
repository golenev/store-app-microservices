package org.golenev.commondto

/** Сохранённая операция оформления. Пока публикация не подтверждена, publishedAt отсутствует. */
data class Submission(val storeId: String, val submissionId: String, val cartId: String,
                      val eventId: String, val publicationStatus: PublicationStatus,
                      val acceptedAt: String, val publishedAt: String? = null)
