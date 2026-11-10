package cl.duoc.cloudnative.lambda;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.contracts.PaymentResultEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

public class EmailReceiptHandler implements RequestHandler<SQSEvent, Void> {
    private final S3Client s3Client;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String receiptsBucket;
    private final String notificationTopicArn;
    private final String statusTopicArn;
    private final DynamoDbClient dynamoDbClient;
    private final String ordersTableName;

    public EmailReceiptHandler() {
        this(
                S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                SnsClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("RECEIPTS_BUCKET"),
                System.getenv("NOTIFICATION_TOPIC_ARN"),
                System.getenv("PAYMENT_STATUS_TOPIC_ARN"),
                DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("ORDERS_TABLE_NAME"));
    }

    EmailReceiptHandler(
            S3Client s3Client,
            SnsClient snsClient,
            String receiptsBucket,
            String notificationTopicArn,
            String statusTopicArn,
            DynamoDbClient dynamoDbClient,
            String ordersTableName) {
        this.s3Client = s3Client;
        this.snsClient = snsClient;
        this.receiptsBucket = required(receiptsBucket, "RECEIPTS_BUCKET");
        this.notificationTopicArn = required(notificationTopicArn, "NOTIFICATION_TOPIC_ARN");
        this.statusTopicArn = statusTopicArn;
        this.dynamoDbClient = dynamoDbClient;
        this.ordersTableName = ordersTableName;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            processOrder(message.getBody());
        }
        return null;
    }

    private void processOrder(String body) {
        OrderCreatedEvent order;
        try {
            order = objectMapper.readValue(body, OrderCreatedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("El mensaje SQS no contiene un pedido válido", exception);
        }
        if (order.sku().toLowerCase(java.util.Locale.ROOT).startsWith("falla")) {
            throw new IllegalStateException("Falla simulada para probar la DLQ: " + order.sku());
        }

        byte[] receipt;
        String statusMessage = null;
        try {
            receipt = objectMapper.writeValueAsBytes(order);
            if (statusTopicArn != null && !statusTopicArn.isBlank()) {
                PaymentResultEvent result = new PaymentResultEvent(
                        UUID.randomUUID(), order.orderId(), "PAID", "Pago procesado por Lambda", Instant.now());
                statusMessage = objectMapper.writeValueAsString(result);
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar el comprobante o el resultado de pago", exception);
        }

        String key = "receipts/orders/" + order.orderId() + ".json";
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(receiptsBucket)
                        .key(key)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromBytes(receipt));
        if (statusMessage != null) {
            snsClient.publish(PublishRequest.builder()
                    .topicArn(statusTopicArn)
                    .message(statusMessage)
                    .build());
        }
        snsClient.publish(PublishRequest.builder()
                .topicArn(notificationTopicArn)
                .subject("Pedido #" + order.orderId() + " procesado")
                .message("El pedido #" + order.orderId() + " fue procesado. Comprobante: " + key)
                .build());
        if (ordersTableName != null && !ordersTableName.isBlank()) {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(ordersTableName)
                    .key(java.util.Map.of(
                            "id", AttributeValue.builder().s(Long.toString(order.orderId())).build()))
                    .updateExpression("SET #status = :paid")
                    .conditionExpression("attribute_exists(id)")
                    .expressionAttributeNames(java.util.Map.of("#status", "status"))
                    .expressionAttributeValues(java.util.Map.of(
                            ":paid", AttributeValue.builder().s("PAID").build()))
                    .build());
        } else {
            throw new IllegalStateException("Falta configurar ORDERS_TABLE_NAME");
        }

        System.out.println("Pedido procesado: orderId=" + order.orderId() + ", receipt=" + key);
    }

    private static String required(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar " + variable);
        }
        return value;
    }
}
