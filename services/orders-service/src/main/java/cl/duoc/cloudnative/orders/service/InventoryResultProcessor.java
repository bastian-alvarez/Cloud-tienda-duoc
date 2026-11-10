package cl.duoc.cloudnative.orders.service;

import cl.duoc.cloudnative.contracts.InventoryResultEvent;
import cl.duoc.cloudnative.orders.domain.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryResultProcessor {
    private final OrderRepository orderRepository;

    public InventoryResultProcessor(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void apply(InventoryResultEvent event) {
        if (!"RESERVED".equals(event.status())) {
            throw new IllegalArgumentException("Estado de inventario desconocido: " + event.status());
        }
        var order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Pedido inexistente: " + event.orderId()));
        order.markInventoryReserved();
    }
}
