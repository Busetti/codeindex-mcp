package com.example.orders.domain;

public class Order {
    private Long id;
    private Long customerId;
    private double total;

    public Order(Long id, Long customerId, double total) {
        this.id = id;
        this.customerId = customerId;
        this.total = total;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public double getTotal() { return total; }
}
