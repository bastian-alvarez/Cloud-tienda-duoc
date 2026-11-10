package cl.duoc.cloudnative.orders.messaging;

import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Component
@Profile("!aws")
public class LocalQueueBootstrap implements ApplicationRunner {
    private static final Map<String, String> QUEUE_NAMES = Map.of(
            "payment", "tiendaduoc-payment",
            "inventory", "tiendaduoc-inventory",
            "notifications", "tiendaduoc-notifications",
            "payment-results", "tiendaduoc-payment-results",
            "inventory-results", "tiendaduoc-inventory-results");

    private final SqsClient sqsClient;

    public LocalQueueBootstrap(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        QUEUE_NAMES.values().forEach(this::createQueueWithDlq);
    }

    private void createQueueWithDlq(String queueName) {
        String dlqName = queueName + "-dlq";
        String dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(dlqName)
                .attributes(Map.of(QueueAttributeName.SQS_MANAGED_SSE_ENABLED, "true"))
                .build()).queueUrl();
        String dlqArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build()).attributes().get(QueueAttributeName.QUEUE_ARN);
        sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.SQS_MANAGED_SSE_ENABLED, "true",
                        QueueAttributeName.VISIBILITY_TIMEOUT, "180",
                        QueueAttributeName.REDRIVE_POLICY,
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}"))
                .build());
    }
}
