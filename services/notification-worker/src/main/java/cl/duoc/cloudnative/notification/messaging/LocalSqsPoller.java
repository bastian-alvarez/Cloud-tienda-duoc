package cl.duoc.cloudnative.notification.messaging;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@Profile("!lambda")
public class LocalSqsPoller {
    private static final Logger log = LoggerFactory.getLogger(LocalSqsPoller.class);
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final String queueUrl;

    public LocalSqsPoller(
            SqsClient sqsClient,
            @Value("${tienda.aws.notification-queue-url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${AWS_POLL_DELAY_MS:1000}")
    public void poll() {
        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build());
        for (var message : response.messages()) {
            try {
                OrderCreatedEvent order = objectMapper.readValue(message.body(), OrderCreatedEvent.class);
                log.info("Notificación simulada: email={}, orderId={}",
                        order.customerEmail(), order.orderId());
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            } catch (Exception exception) {
                log.error("No se pudo procesar la notificación; SQS reintentará el mensaje", exception);
            }
        }
    }
}
