package cl.duoc.cloudnative.orders.messaging;

import cl.duoc.cloudnative.contracts.InventoryResultEvent;
import cl.duoc.cloudnative.contracts.PaymentResultEvent;
import cl.duoc.cloudnative.orders.service.InventoryResultProcessor;
import cl.duoc.cloudnative.orders.service.PaymentResultProcessor;
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

@Component
@Profile("!lambda")
public class LocalSqsResultsPoller {
    private static final Logger log = LoggerFactory.getLogger(LocalSqsResultsPoller.class);
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final PaymentResultProcessor paymentProcessor;
    private final InventoryResultProcessor inventoryProcessor;
    private final String paymentQueueUrl;
    private final String inventoryQueueUrl;

    public LocalSqsResultsPoller(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            PaymentResultProcessor paymentProcessor,
            InventoryResultProcessor inventoryProcessor,
            @Value("${tienda.aws.payment-results-queue-url:http://localhost:4566/000000000000/tiendaduoc-payment-results}")
                    String paymentQueueUrl,
            @Value("${tienda.aws.inventory-results-queue-url:http://localhost:4566/000000000000/tiendaduoc-inventory-results}")
                    String inventoryQueueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.paymentProcessor = paymentProcessor;
        this.inventoryProcessor = inventoryProcessor;
        this.paymentQueueUrl = paymentQueueUrl;
        this.inventoryQueueUrl = inventoryQueueUrl;
    }

    @Scheduled(fixedDelayString = "${AWS_POLL_DELAY_MS:1000}")
    public void pollResults() {
        pollPaymentResults();
        pollInventoryResults();
    }

    private void pollPaymentResults() {
        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(paymentQueueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build());
        for (var message : response.messages()) {
            try {
                paymentProcessor.apply(objectMapper.readValue(message.body(), PaymentResultEvent.class));
                delete(paymentQueueUrl, message.receiptHandle());
            } catch (Exception exception) {
                log.error("No se pudo aplicar resultado de pago; SQS reintentará el mensaje", exception);
            }
        }
    }

    private void pollInventoryResults() {
        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(inventoryQueueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build());
        for (var message : response.messages()) {
            try {
                inventoryProcessor.apply(objectMapper.readValue(message.body(), InventoryResultEvent.class));
                delete(inventoryQueueUrl, message.receiptHandle());
            } catch (Exception exception) {
                log.error("No se pudo aplicar resultado de inventario; SQS reintentará el mensaje", exception);
            }
        }
    }

    private void delete(String queueUrl, String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
