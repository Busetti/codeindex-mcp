package com.example.orders.service;

import com.example.orders.client.EnrichmentClient;
import com.example.orders.domain.Customer;
import com.example.orders.domain.Order;
import com.example.orders.dto.OrderView;
import com.example.orders.repo.CustomerRepository;
import com.example.orders.repo.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final EnrichmentClient enrichmentClient;

    public OrderService(OrderRepository orderRepository,
                        CustomerRepository customerRepository,
                        EnrichmentClient enrichmentClient) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.enrichmentClient = enrichmentClient;
    }

    /**
     * PERF SMELLS (intentional, for triage demo):
     *  - findAll() with no Pageable -> unbounded result set.
     *  - customerRepository.findById(...) inside the loop -> classic N+1.
     *  - enrichmentClient.fetchRating(...) HTTP call inside the loop -> per-row remote latency.
     */
    @Transactional
    public List<OrderView> listAllOrders() {
        List<Order> orders = orderRepository.findAll();
        List<OrderView> views = new ArrayList<>();
        for (Order order : orders) {
            Customer customer = customerRepository.findById(order.getCustomerId());
            int rating = enrichmentClient.fetchRating(customer.getId());
            views.add(new OrderView(order.getId(), customer.getName(), order.getTotal(), rating));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public OrderView getOrder(Long id) {
        Order order = orderRepository.findByIdOrThrow(id);
        Customer customer = customerRepository.findById(order.getCustomerId());
        return new OrderView(order.getId(), customer.getName(), order.getTotal(), 0);
    }
}
