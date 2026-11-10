package cl.duoc.cloudnative.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "orders")
@DynamicUpdate
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String customerEmail;

    @Column(nullable = false, length = 80)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean inventoryReserved;

    @Column(nullable = false)
    private Instant createdAt;

    protected Order() {
    }

    public Order(String customerEmail, String sku, int quantity, BigDecimal total) {
        this.customerEmail = customerEmail;
        this.sku = sku;
        this.quantity = quantity;
        this.total = total;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public OrderStatus getStatus() {
        if (status == OrderStatus.PAID && inventoryReserved) {
            return OrderStatus.COMPLETED;
        }
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
    }

    public void markInventoryReserved() {
        inventoryReserved = true;
    }
}
