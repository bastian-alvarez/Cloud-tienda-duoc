package cl.duoc.cloudnative.orders.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank @Email @Size(max = 254) String customerEmail,
        @NotBlank @Size(max = 80) String sku,
        @Positive int quantity,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice
) {
}

