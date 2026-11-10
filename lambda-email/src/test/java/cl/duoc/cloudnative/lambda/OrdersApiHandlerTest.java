package cl.duoc.cloudnative.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrdersApiHandlerTest {
    @Test
    void acceptsValidOrder() {
        var request = new OrdersApiHandler.CreateOrderRequest(
                "alumno@example.com", "POLERA-DUOC-M", 2, new BigDecimal("9990"));

        assertNull(OrdersApiHandler.validationError(request));
    }

    @Test
    void rejectsInvalidCustomerEmail() {
        var request = new OrdersApiHandler.CreateOrderRequest(
                "correo-invalido", "POLERA-DUOC-M", 1, new BigDecimal("1000"));

        assertEquals(
                "customerEmail debe ser una dirección de correo electrónico válida.",
                OrdersApiHandler.validationError(request));
    }

    @Test
    void rejectsNonPositiveQuantityAndPrice() {
        var quantityRequest = new OrdersApiHandler.CreateOrderRequest(
                "alumno@example.com", "POLERA-DUOC-M", 0, new BigDecimal("1000"));
        var priceRequest = new OrdersApiHandler.CreateOrderRequest(
                "alumno@example.com", "POLERA-DUOC-M", 1, BigDecimal.ZERO);

        assertEquals("quantity debe ser mayor que cero.", OrdersApiHandler.validationError(quantityRequest));
        assertEquals("unitPrice debe ser al menos 0.01.", OrdersApiHandler.validationError(priceRequest));
    }

    @Test
    void rejectsUnitPriceBelowOneCent() {
        var request = new OrdersApiHandler.CreateOrderRequest(
                "alumno@example.com", "POLERA-DUOC-M", 1, new BigDecimal("0.009"));

        assertEquals("unitPrice debe ser al menos 0.01.", OrdersApiHandler.validationError(request));
    }

    @Test
    void enforcesOrderTotalLimit() {
        assertEquals(
                new BigDecimal("9999999999.99"),
                OrdersApiHandler.calculateTotal(new BigDecimal("9999999999.99"), 1));
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> OrdersApiHandler.calculateTotal(new BigDecimal("10000000000.00"), 1));
        assertEquals("El total del pedido excede el máximo permitido.", exception.getMessage());
    }
}
