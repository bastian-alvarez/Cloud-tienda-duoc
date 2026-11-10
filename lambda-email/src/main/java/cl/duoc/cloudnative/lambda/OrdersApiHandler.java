package cl.duoc.cloudnative.lambda;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class OrdersApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final BigDecimal MIN_UNIT_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAX_ORDER_TOTAL = new BigDecimal("9999999999.99");

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String ordersTableName;
    private final String ordersQueueUrl;

    public OrdersApiHandler() {
        this(
                DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                SqsClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("ORDERS_TABLE_NAME"),
                System.getenv("ORDERS_QUEUE_URL"));
    }

    OrdersApiHandler(
            DynamoDbClient dynamoDbClient,
            SqsClient sqsClient,
            String ordersTableName,
            String ordersQueueUrl) {
        this.dynamoDbClient = dynamoDbClient;
        this.sqsClient = sqsClient;
        this.ordersTableName = required(ordersTableName, "ORDERS_TABLE_NAME");
        this.ordersQueueUrl = required(ordersQueueUrl, "ORDERS_QUEUE_URL");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            String path = event.getRawPath();
            if ("POST".equalsIgnoreCase(method) && "/api/orders".equals(path)) {
                return createOrder(event);
            }
            if ("GET".equalsIgnoreCase(method) && "/api/orders".equals(path)) {
                return listOrders();
            }
            if ("GET".equalsIgnoreCase(method) && path.matches("/api/orders/[^/]+")) {
                return findOrder(path.substring("/api/orders/".length()));
            }
            return response(404, Map.of("title", "Not Found", "status", 404));
        } catch (ValidationException exception) {
            return response(400, Map.of(
                    "type", "about:blank",
                    "title", "Bad Request",
                    "status", 400,
                    "detail", exception.getMessage()));
        } catch (OrderNotFoundException exception) {
            return response(404, Map.of(
                    "type", "about:blank",
                    "title", "Not Found",
                    "status", 404,
                    "detail", exception.getMessage()));
        } catch (Exception exception) {
            System.err.println("Orders API request failed: " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
            return response(500, Map.of(
                    "type", "about:blank",
                    "title", "Internal Server Error",
                    "status", 500,
                    "detail", "No se pudo completar la solicitud."));
        }
    }

    private APIGatewayV2HTTPResponse createOrder(APIGatewayV2HTTPEvent event) throws Exception {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw new ValidationException("El cuerpo de la solicitud es obligatorio.");
        }
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), java.nio.charset.StandardCharsets.UTF_8);
        }

        CreateOrderRequest request;
        try {
            request = objectMapper.readValue(body, CreateOrderRequest.class);
        } catch (JsonProcessingException exception) {
            throw new ValidationException("El cuerpo debe ser un JSON válido.");
        }
        String validationError = validationError(request);
        if (validationError != null) {
            throw new ValidationException(validationError);
        }
        BigDecimal total = calculateTotal(request.unitPrice(), request.quantity());
        Instant createdAt = Instant.now();
        long id;
        int attempts = 0;
        while (true) {
            id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            try {
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(ordersTableName)
                        .item(orderItem(
                                id,
                                request.customerEmail(),
                                request.sku(),
                                request.quantity(),
                                total,
                                "PENDING",
                                createdAt))
                        .conditionExpression("attribute_not_exists(id)")
                        .build());
                break;
            } catch (ConditionalCheckFailedException exception) {
                attempts++;
                if (attempts >= 3) {
                    throw exception;
                }
            }
        }

        OrderCreatedEvent orderEvent = new OrderCreatedEvent(
                UUID.randomUUID(),
                id,
                request.customerEmail(),
                request.sku(),
                request.quantity(),
                total,
                createdAt);
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(ordersQueueUrl)
                .messageBody(objectMapper.writeValueAsString(orderEvent))
                .build());

        Map<String, Object> response = orderResponse(
                id, request.customerEmail(), request.sku(), request.quantity(), total, "PENDIENTE", createdAt);
        return response(201, response, Map.of("Location", "/api/orders/" + id));
    }

    private APIGatewayV2HTTPResponse listOrders() {
        List<Map<String, Object>> orders = new ArrayList<>();
        ScanRequest request = ScanRequest.builder().tableName(ordersTableName).build();
        while (true) {
            ScanResponse result = dynamoDbClient.scan(request);
            for (Map<String, AttributeValue> item : result.items()) {
                orders.add(readOrder(item));
            }
            if (result.lastEvaluatedKey() == null || result.lastEvaluatedKey().isEmpty()) {
                break;
            }
            request = request.toBuilder().exclusiveStartKey(result.lastEvaluatedKey()).build();
        }
        orders.sort((first, second) ->
                ((Instant) second.get("createdAt")).compareTo((Instant) first.get("createdAt")));
        return response(200, orders);
    }

    private APIGatewayV2HTTPResponse findOrder(String idText) {
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (NumberFormatException exception) {
            throw new ValidationException("El identificador del pedido debe ser numérico.");
        }
        var result = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(ordersTableName)
                .key(Map.of("id", AttributeValue.builder().s(Long.toString(id)).build()))
                .build());
        if (result.item().isEmpty()) {
            throw new OrderNotFoundException(id);
        }
        return response(200, readOrder(result.item()));
    }

    static String validationError(CreateOrderRequest request) {
        if (request == null) {
            return "El cuerpo de la solicitud debe incluir un pedido.";
        }
        if (request.customerEmail() == null
                || request.customerEmail().length() > 254
                || !EMAIL_PATTERN.matcher(request.customerEmail()).matches()) {
            return "customerEmail debe ser una dirección de correo electrónico válida.";
        }
        if (request.sku() == null || request.sku().isBlank() || request.sku().length() > 80) {
            return "sku es obligatorio y debe tener 80 caracteres o menos.";
        }
        if (request.quantity() <= 0) {
            return "quantity debe ser mayor que cero.";
        }
        if (request.unitPrice() == null || request.unitPrice().compareTo(MIN_UNIT_PRICE) < 0) {
            return "unitPrice debe ser al menos 0.01.";
        }
        return null;
    }

    static BigDecimal calculateTotal(BigDecimal unitPrice, int quantity) {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(MAX_ORDER_TOTAL) > 0) {
            throw new ValidationException("El total del pedido excede el máximo permitido.");
        }
        return total;
    }

    private Map<String, AttributeValue> orderItem(
            long id,
            String customerEmail,
            String sku,
            int quantity,
            BigDecimal total,
            String status,
            Instant createdAt) {
        return Map.of(
                "id", AttributeValue.builder().s(Long.toString(id)).build(),
                "customerEmail", AttributeValue.builder().s(customerEmail).build(),
                "sku", AttributeValue.builder().s(sku).build(),
                "quantity", AttributeValue.builder().n(Integer.toString(quantity)).build(),
                "total", AttributeValue.builder().n(total.toPlainString()).build(),
                "status", AttributeValue.builder().s(status).build(),
                "createdAt", AttributeValue.builder().s(createdAt.toString()).build());
    }

    private Map<String, Object> readOrder(Map<String, AttributeValue> item) {
        long id = Long.parseLong(item.get("id").s());
        String customerEmail = item.get("customerEmail").s();
        String sku = item.get("sku").s();
        int quantity = Integer.parseInt(item.get("quantity").n());
        BigDecimal total = new BigDecimal(item.get("total").n());
        String status = apiStatus(item.get("status").s());
        Instant createdAt = Instant.parse(item.get("createdAt").s());
        return orderResponse(id, customerEmail, sku, quantity, total, status, createdAt);
    }

    private Map<String, Object> orderResponse(
            long id,
            String customerEmail,
            String sku,
            int quantity,
            BigDecimal total,
            String status,
            Instant createdAt) {
        Map<String, Object> order = new HashMap<>();
        order.put("id", id);
        order.put("customerEmail", customerEmail);
        order.put("sku", sku);
        order.put("quantity", quantity);
        order.put("total", total);
        order.put("status", status);
        order.put("createdAt", createdAt);
        return order;
    }

    private String apiStatus(String status) {
        return switch (status) {
            case "PAID" -> "PAGADO";
            case "PAYMENT_REJECTED" -> "PAGO_RECHAZADO";
            default -> "PENDIENTE";
        };
    }

    private APIGatewayV2HTTPResponse response(int status, Object body) {
        return response(status, body, Map.of());
    }

    private APIGatewayV2HTTPResponse response(int status, Object body, Map<String, String> additionalHeaders) {
        Map<String, String> headers = new HashMap<>(additionalHeaders);
        headers.put("Content-Type", "application/json");
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(headers)
                    .withBody(objectMapper.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar la respuesta HTTP", exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar " + name);
        }
        return value;
    }

    record CreateOrderRequest(String customerEmail, String sku, int quantity, BigDecimal unitPrice) {
    }

    private static final class ValidationException extends IllegalArgumentException {
        ValidationException(String message) {
            super(message);
        }
    }

    private static final class OrderNotFoundException extends RuntimeException {
        OrderNotFoundException(long id) {
            super("No existe el pedido " + id);
        }
    }
}
