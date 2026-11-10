package cl.duoc.cloudnative.orders.aws;

import cl.duoc.cloudnative.contracts.PaymentResultEvent;
import cl.duoc.cloudnative.orders.service.PaymentResultProcessor;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PaymentResultLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private final PaymentResultProcessor processor =
            OrdersLambdaContext.getBean(PaymentResultProcessor.class);
    private final ObjectMapper objectMapper = OrdersLambdaContext.getBean(ObjectMapper.class);

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processor.apply(objectMapper.readValue(message.getBody(), PaymentResultEvent.class));
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "No se pudo aplicar resultado de pago; SQS reintentará y luego usará la DLQ",
                        exception);
            }
        }
        return null;
    }
}
