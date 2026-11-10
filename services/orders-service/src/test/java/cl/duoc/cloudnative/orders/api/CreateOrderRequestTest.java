package cl.duoc.cloudnative.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateOrderRequestTest {
    private static Validator validator;
    private static jakarta.validation.ValidatorFactory validatorFactory;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void rejectsInvalidOrderData() {
        var request = new CreateOrderRequest("not-an-email", "", 0, BigDecimal.ZERO);

        assertThat(validator.validate(request)).hasSize(4);
    }

    @Test
    void acceptsValidOrderData() {
        var request = new CreateOrderRequest("student@example.com", "POLERA-M", 2, new BigDecimal("9990"));

        assertThat(validator.validate(request)).isEmpty();
    }
}
