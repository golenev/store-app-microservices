package com.shop.store.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Составной первичный ключ таблицы {@code incoming_goods_diagnostics}; равенство определяется всеми его полями. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class IncomingDiagnosticId implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Топик исходного сообщения. */
    private String topic;
    /** Раздел Kafka. */
    private int partitionId;
    /** Позиция сообщения в разделе. */
    private long kafkaOffset;
}
