package cl.duoc.cloudnative.admin.service;

import cl.duoc.cloudnative.admin.api.QueueRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Service
public class QueueAdminService {
    private final SqsClient sqsClient;

    public QueueAdminService(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
    }

    public List<String> listQueues() {
        return sqsClient.listQueues(ListQueuesRequest.builder().build()).queueUrls();
    }

    public String createQueue(QueueRequest request) {
        return sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(request.name())
                .attributes(java.util.Map.of(QueueAttributeName.SQS_MANAGED_SSE_ENABLED, "true"))
                .build()).queueUrl();
    }

    public void deleteQueue(String queueName) {
        String queueUrl = sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build()).queueUrl();
        sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }
}
