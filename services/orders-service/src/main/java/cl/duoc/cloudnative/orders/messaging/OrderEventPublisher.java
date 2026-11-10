package cl.duoc.cloudnative.orders.messaging;

import cl.duoc.cloudnative.contracts.OrderCreatedEvent;

public interface OrderEventPublisher {
    void publish(OrderCreatedEvent event);
}
