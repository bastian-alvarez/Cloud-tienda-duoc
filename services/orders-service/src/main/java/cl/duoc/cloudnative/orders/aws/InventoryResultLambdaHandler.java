package cl.duoc.cloudnative.orders.aws;

import cl.duoc.cloudnative.contracts.InventoryResultEvent;
import cl.duoc.cloudnative.orders.service.InventoryResultProcessor;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

public class InventoryResultLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private final InventoryResultProcessor processor =
            OrdersLambdaContext.getBean(InventoryResultProcessor.class);
    private final ObjectMapper objectMapper = OrdersLambdaContext.getBean(ObjectMapper.class);

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processor.apply(objectMapper.readValue(message.getBody(), InventoryResultEvent.class));
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "No se pudo aplicar resultado de inventario; SQS reintentará y luego usará la DLQ",
                        exception);
            }
        }
        return null;
    }
}
