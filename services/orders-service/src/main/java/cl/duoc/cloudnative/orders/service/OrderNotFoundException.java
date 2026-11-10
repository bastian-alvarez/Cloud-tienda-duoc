package cl.duoc.cloudnative.orders.service;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("No existe el pedido " + id);
    }
}

