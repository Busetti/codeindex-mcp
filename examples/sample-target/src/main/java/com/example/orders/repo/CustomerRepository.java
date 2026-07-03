package com.example.orders.repo;

import com.example.orders.domain.Customer;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    // Stand-in for a Spring Data method; the point is it is called once per order (N+1).
    public Customer findById(Long id) {
        return new Customer(id, "customer-" + id);
    }
}
