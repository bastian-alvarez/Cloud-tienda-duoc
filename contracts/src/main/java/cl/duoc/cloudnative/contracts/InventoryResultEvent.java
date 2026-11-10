package cl.duoc.cloudnative.contracts;

import java.time.Instant;
import java.util.UUID;

public record InventoryResultEvent(
        UUID eventId,
        Long orderId,
        String status,
        String detail,
        Instant occurredAt
) {
}
