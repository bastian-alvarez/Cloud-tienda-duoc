package cl.duoc.cloudnative.orders.api;

import cl.duoc.cloudnative.orders.domain.Order;
import cl.duoc.cloudnative.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long id,
        String customerEmail,
        String sku,
        int quantity,
        BigDecimal total,
        String status,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerEmail(),
                order.getSku(),
                order.getQuantity(),
                order.getTotal(),
                apiStatus(order.getStatus()),
                order.getCreatedAt());
    }

    private static String apiStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> "PENDIENTE";
            case PAID -> "PAGADO";
            case PAYMENT_REJECTED -> "PAGO_RECHAZADO";
            case COMPLETED -> "COMPLETADO";
        };
    }
}
