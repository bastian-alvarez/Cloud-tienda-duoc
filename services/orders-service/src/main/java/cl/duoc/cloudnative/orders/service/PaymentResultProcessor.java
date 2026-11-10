package cl.duoc.cloudnative.orders.service;

import cl.duoc.cloudnative.contracts.PaymentResultEvent;
import cl.duoc.cloudnative.orders.domain.OrderRepository;
import cl.duoc.cloudnative.orders.domain.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentResultProcessor {
    private final OrderRepository orderRepository;

    public PaymentResultProcessor(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void apply(PaymentResultEvent event) {
        var order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Pedido inexistente: " + event.orderId()));
        if (order.getStatus() == OrderStatus.PENDING) {
            OrderStatus status = "PAID".equals(event.status())
                    ? OrderStatus.PAID
                    : OrderStatus.PAYMENT_REJECTED;
            order.updateStatus(status);
        }
    }
}
