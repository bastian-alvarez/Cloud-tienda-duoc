package cl.duoc.cloudnative.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        String customerEmail,
        String sku,
        int quantity,
        BigDecimal total,
        Instant occurredAt
) {
}

