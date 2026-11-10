package cl.duoc.cloudnative.notification.aws;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class NotificationSqsLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SnsClient snsClient = SnsClient.builder().build();
    private final String notificationTopicArn = requiredEnvironmentVariable("NOTIFICATION_TOPIC_ARN");

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                OrderCreatedEvent order = objectMapper.readValue(message.getBody(), OrderCreatedEvent.class);
                String subject = "Confirmación de pedido #" + order.orderId();
                String body = """
                        TiendaDuoc - Confirmación de pedido

                        Pedido: #%d
                        Cliente: %s
                        Producto: %s
                        Cantidad: %d
                        Total: $%s

                        Este correo es una confirmación de prueba académica.
                        """.formatted(
                        order.orderId(),
                        order.customerEmail(),
                        order.sku(),
                        order.quantity(),
                        order.total().toPlainString());
                snsClient.publish(PublishRequest.builder()
                        .topicArn(notificationTopicArn)
                        .subject(subject)
                        .message(body)
                        .build());
                System.out.printf("Confirmación publicada en SNS: email=%s, orderId=%d%n",
                        order.customerEmail(), order.orderId());
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "No se pudo procesar la notificación; SQS reintentará y luego usará la DLQ",
                        exception);
            }
        }
        return null;
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno requerida: " + name);
        }
        return value;
    }
}
