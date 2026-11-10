package cl.duoc.cloudnative.inventory.service;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.contracts.InventoryResultEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryProcessor {
    private static final Logger log = LoggerFactory.getLogger(InventoryProcessor.class);
    private final ProcessedEventStore processedEventStore;

    public InventoryProcessor(ProcessedEventStore processedEventStore) {
        this.processedEventStore = processedEventStore;
    }

    @Transactional
    public InventoryResultEvent process(OrderCreatedEvent event) {
        if (!processedEventStore.markIfNew(event.eventId())) {
            log.info("Evento de inventario duplicado ignorado: eventId={}", event.eventId());
            return null;
        }
        if (event.sku().startsWith("FAIL-STOCK-")
                || event.sku().toLowerCase(Locale.ROOT).startsWith("falla")) {
            throw new IllegalStateException("Falla simulada de inventario para pedido " + event.orderId());
        }
        log.info("Reserva de inventario simulada: orderId={}, sku={}, cantidad={}",
                event.orderId(), event.sku(), event.quantity());
        return new InventoryResultEvent(
                UUID.randomUUID(), event.orderId(), "RESERVED", "Inventario reservado", Instant.now());
    }
}
