package com.example.orders.dto;

public record OrderView(Long orderId, String customerName, double total, int rating) {
}
