package com.shop.store.model;

import java.util.UUID;

/**
 * Данные принятого запроса для сравнения повтора.
 * @param submissionId принятая заявка
 * @param requestFingerprint контрольная сумма исходного запроса
 */
public record SubmissionMatch(UUID submissionId, String requestFingerprint) { }
