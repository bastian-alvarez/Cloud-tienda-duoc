package cl.duoc.cloudnative.inventory.aws;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.inventory.InventoryWorkerApplication;
import cl.duoc.cloudnative.inventory.service.InventoryProcessor;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class InventorySqsLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private static final ConfigurableApplicationContext APPLICATION =
            new SpringApplicationBuilder(InventoryWorkerApplication.class)
                    .profiles("aws", "lambda")
                    .web(WebApplicationType.NONE)
                    .run();

    private final ObjectMapper objectMapper = APPLICATION.getBean(ObjectMapper.class);
    private final InventoryProcessor processor = APPLICATION.getBean(InventoryProcessor.class);
    private final SqsClient sqsClient = APPLICATION.getBean(SqsClient.class);
    private final String resultQueueUrl = required("INVENTORY_RESULTS_QUEUE_URL");

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                OrderCreatedEvent order = objectMapper.readValue(message.getBody(), OrderCreatedEvent.class);
                var result = processor.process(order);
                if (result != null) {
                    sqsClient.sendMessage(SendMessageRequest.builder()
                            .queueUrl(resultQueueUrl)
                            .messageBody(objectMapper.writeValueAsString(result))
                            .build());
                }
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Falló inventario; SQS reintentará y luego enviará el mensaje a la DLQ",
                        exception);
            }
        }
        return null;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar " + name);
        }
        return value;
    }
}
