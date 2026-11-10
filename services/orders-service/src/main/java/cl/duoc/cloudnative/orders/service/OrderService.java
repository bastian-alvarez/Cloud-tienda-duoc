package cl.duoc.cloudnative.orders.service;

import cl.duoc.cloudnative.orders.api.CreateOrderRequest;
import cl.duoc.cloudnative.orders.api.OrderResponse;
import cl.duoc.cloudnative.contracts.OrderCreatedEvent;
import cl.duoc.cloudnative.orders.domain.Order;
import cl.duoc.cloudnative.orders.domain.OrderRepository;
import cl.duoc.cloudnative.orders.messaging.OrderEventPublisher;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {
    private final OrderRepository repository;
    private final OrderEventPublisher publisher;

    public OrderService(OrderRepository repository, OrderEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        var total = request.unitPrice()
                .multiply(java.math.BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        Order order = repository.save(new Order(
                request.customerEmail(), request.sku(), request.quantity(), total));
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID(),
                order.getId(),
                order.getCustomerEmail(),
                order.getSku(),
                order.getQuantity(),
                order.getTotal(),
                Instant.now());
        publishAfterCommit(event);
        return OrderResponse.from(order);
    }

    private void publishAfterCommit(OrderCreatedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publish(event);
            }
        });
    }

    @Transactional(readOnly = true)
    public OrderResponse find(Long id) {
        return repository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return repository.findAll().stream().map(OrderResponse::from).toList();
    }
}
