package cl.duoc.cloudnative.orders.messaging;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
public class AwsOrderPublisher implements OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(AwsOrderPublisher.class);
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final java.util.List<String> queueUrls;

    public AwsOrderPublisher(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            @Value("${tienda.aws.payment-queue-url}") String paymentQueueUrl,
            @Value("${tienda.aws.inventory-queue-url}") String inventoryQueueUrl,
            @Value("${tienda.aws.notification-queue-url}") String notificationQueueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrls = java.util.List.of(paymentQueueUrl, inventoryQueueUrl, notificationQueueUrl);
    }

    @Override
    public void publish(OrderCreatedEvent event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            for (String queueUrl : queueUrls) {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .build());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar el evento del pedido", exception);
        }
        log.info("Evento OrderCreated publicado en colas SQS: orderId={}, eventId={}",
                event.orderId(), event.eventId());
    }
}
