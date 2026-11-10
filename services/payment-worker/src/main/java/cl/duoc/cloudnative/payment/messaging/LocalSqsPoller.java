package cl.duoc.cloudnative.payment.messaging;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.payment.service.PaymentProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@Profile("!lambda")
public class LocalSqsPoller {
    private static final Logger log = LoggerFactory.getLogger(LocalSqsPoller.class);
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final PaymentProcessor processor;
    private final String queueUrl;
    private final String resultQueueUrl;

    public LocalSqsPoller(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            PaymentProcessor processor,
            @Value("${tienda.aws.payment-queue-url}") String queueUrl,
            @Value("${tienda.aws.payment-results-queue-url}") String resultQueueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.queueUrl = queueUrl;
        this.resultQueueUrl = resultQueueUrl;
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
                var result = processor.process(objectMapper.readValue(message.body(), OrderCreatedEvent.class));
                if (result != null) {
                    sqsClient.sendMessage(SendMessageRequest.builder()
                            .queueUrl(resultQueueUrl)
                            .messageBody(objectMapper.writeValueAsString(result))
                            .build());
                }
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            } catch (Exception exception) {
                log.error("Falló el pago; SQS reintentará y luego enviará el mensaje a la DLQ", exception);
            }
        }
    }
}
