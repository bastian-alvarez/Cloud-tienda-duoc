package cl.duoc.cloudnative.payment.aws;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.payment.PaymentWorkerApplication;
import cl.duoc.cloudnative.payment.service.PaymentProcessor;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class PaymentSqsLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private static final ConfigurableApplicationContext APPLICATION =
            new SpringApplicationBuilder(PaymentWorkerApplication.class)
                    .profiles("aws", "lambda")
                    .web(WebApplicationType.NONE)
                    .run();

    private final ObjectMapper objectMapper = APPLICATION.getBean(ObjectMapper.class);
    private final PaymentProcessor processor = APPLICATION.getBean(PaymentProcessor.class);
    private final SqsClient sqsClient = APPLICATION.getBean(SqsClient.class);
    private final String resultQueueUrl = required("PAYMENT_RESULTS_QUEUE_URL");

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
                        "Falló el pago; SQS reintentará y luego enviará el mensaje a la DLQ",
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
