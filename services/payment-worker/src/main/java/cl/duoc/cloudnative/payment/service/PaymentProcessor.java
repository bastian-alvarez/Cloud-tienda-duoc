package cl.duoc.cloudnative.payment.service;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.contracts.PaymentResultEvent;
import java.util.Locale;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentProcessor {
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private final ProcessedEventStore processedEventStore;

    public PaymentProcessor(ProcessedEventStore processedEventStore) {
        this.processedEventStore = processedEventStore;
    }

    @Transactional
    public PaymentResultEvent process(OrderCreatedEvent event) {
        if (!processedEventStore.markIfNew(event.eventId())) {
            log.info("Evento de pago duplicado ignorado: eventId={}", event.eventId());
            return null;
        }
        if (event.sku().startsWith("FAIL-PAYMENT-")
                || event.sku().toLowerCase(Locale.ROOT).startsWith("falla")) {
            throw new IllegalStateException("Falla simulada de la pasarela para pedido " + event.orderId());
        }
        log.info("Pago simulado aprobado: orderId={}, total={}", event.orderId(), event.total());
        return new PaymentResultEvent(
                UUID.randomUUID(), event.orderId(), "PAID", "Pago simulado aprobado", Instant.now());
    }
}
