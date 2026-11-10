package cl.duoc.cloudnative.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cl.duoc.cloudnative.orders.api.OrderResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void showsPaidUntilInventoryReservationCompletesTheOrder() {
        Order order = new Order("alumno@example.com", "SKU-1", 1, new BigDecimal("100.00"));

        order.updateStatus(OrderStatus.PAID);
        assertEquals("PAGADO", OrderResponse.from(order).status());

        order.markInventoryReserved();
        assertEquals("COMPLETADO", OrderResponse.from(order).status());
    }

    @Test
    void completesWhenInventoryArrivesBeforePayment() {
        Order order = new Order("alumno@example.com", "SKU-1", 1, new BigDecimal("100.00"));

        order.markInventoryReserved();
        order.updateStatus(OrderStatus.PAID);

        assertEquals("COMPLETADO", OrderResponse.from(order).status());
    }
}
