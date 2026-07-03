package com.example.orders.web;

import com.example.orders.service.OrderService;
import com.example.orders.dto.OrderView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderView> listOrders() {
        return orderService.listAllOrders();
    }

    @GetMapping("/{id}")
    public OrderView getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }
}
