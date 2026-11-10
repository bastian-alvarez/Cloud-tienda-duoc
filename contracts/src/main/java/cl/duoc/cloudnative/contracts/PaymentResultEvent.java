package cl.duoc.cloudnative.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentResultEvent(
        UUID eventId,
        Long orderId,
        String status,
        String detail,
        Instant occurredAt
) {
}

