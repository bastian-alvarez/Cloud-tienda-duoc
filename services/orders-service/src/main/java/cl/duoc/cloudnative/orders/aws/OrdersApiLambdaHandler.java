package cl.duoc.cloudnative.orders.aws;

import cl.duoc.cloudnative.orders.api.CreateOrderRequest;
import cl.duoc.cloudnative.orders.api.OrderResponse;
import cl.duoc.cloudnative.orders.service.OrderNotFoundException;
import cl.duoc.cloudnative.orders.service.OrderService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class OrdersApiLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private final OrderService orderService = OrdersLambdaContext.getBean(OrderService.class);
    private final ObjectMapper objectMapper = OrdersLambdaContext.getBean(ObjectMapper.class);
    private final Validator validator = OrdersLambdaContext.getBean(Validator.class);

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            String path = event.getRawPath();
            if ("POST".equalsIgnoreCase(method) && "/api/orders".equals(path)) {
                return create(event);
            }
            if ("GET".equalsIgnoreCase(method) && "/api/orders".equals(path)) {
                return response(200, orderService.findAll());
            }
            if ("GET".equalsIgnoreCase(method) && path != null && path.matches("/api/orders/[^/]+")) {
                long id = Long.parseLong(path.substring("/api/orders/".length()));
                return response(200, orderService.find(id));
            }
            return response(404, Map.of("title", "Not Found", "status", 404));
        } catch (OrderNotFoundException | NumberFormatException exception) {
            return response(404, Map.of("title", "Not Found", "status", 404));
        } catch (IllegalArgumentException exception) {
            return response(400, Map.of(
                    "title", "Bad Request",
                    "status", 400,
                    "detail", exception.getMessage()));
        } catch (Exception exception) {
            System.err.println("Orders Lambda failed: " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
            return response(500, Map.of(
                    "title", "Internal Server Error",
                    "status", 500,
                    "detail", "No se pudo completar la solicitud."));
        }
    }

    private APIGatewayV2HTTPResponse create(APIGatewayV2HTTPEvent event) throws Exception {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("El cuerpo de la solicitud es obligatorio.");
        }
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        CreateOrderRequest request = objectMapper.readValue(body, CreateOrderRequest.class);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted(Comparator.naturalOrder())
                    .findFirst()
                    .orElse("Los datos del pedido no son válidos.");
            throw new IllegalArgumentException(detail);
        }
        OrderResponse order = orderService.create(request);
        return response(201, order, Map.of("Location", "/api/orders/" + order.id()));
    }

    private APIGatewayV2HTTPResponse response(int status, Object body) {
        return response(status, body, Map.of());
    }

    private APIGatewayV2HTTPResponse response(int status, Object body, Map<String, String> headers) {
        try {
            var responseHeaders = new java.util.HashMap<>(headers);
            responseHeaders.put("Content-Type", "application/json");
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(responseHeaders)
                    .withBody(objectMapper.writeValueAsString(body))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo serializar la respuesta HTTP", exception);
        }
    }
}
