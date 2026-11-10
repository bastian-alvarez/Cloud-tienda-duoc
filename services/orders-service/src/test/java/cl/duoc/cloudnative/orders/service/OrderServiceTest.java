package cl.duoc.cloudnative.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.duoc.cloudnative.orders.api.CreateOrderRequest;
import cl.duoc.cloudnative.orders.domain.Order;
import cl.duoc.cloudnative.orders.domain.OrderRepository;
import cl.duoc.cloudnative.orders.domain.OrderStatus;
import cl.duoc.cloudnative.orders.messaging.OrderEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderServiceTest {
    private OrderRepository repository;
    private OrderEventPublisher publisher;
    private OrderService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        repository = mock(OrderRepository.class);
        publisher = mock(OrderEventPublisher.class);
        service = new OrderService(repository, publisher);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesCreatedEventOnlyAfterTransactionCommit() {
        Order order = mock(Order.class);
        when(repository.save(any(Order.class))).thenReturn(order);
        when(order.getId()).thenReturn(1L);
        when(order.getCustomerEmail()).thenReturn("alumno@example.com");
        when(order.getSku()).thenReturn("SKU-1");
        when(order.getQuantity()).thenReturn(1);
        when(order.getTotal()).thenReturn(new BigDecimal("9990.00"));
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(order.getCreatedAt()).thenReturn(Instant.now());

        service.create(new CreateOrderRequest(
                "alumno@example.com", "SKU-1", 1, new BigDecimal("9990.00")));

        verify(publisher, never()).publish(any());
        var synchronizations = new ArrayList<>(
                TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(
                cl.duoc.cloudnative.contracts.OrderCreatedEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        assertEquals(1L, eventCaptor.getValue().orderId());
    }
}
