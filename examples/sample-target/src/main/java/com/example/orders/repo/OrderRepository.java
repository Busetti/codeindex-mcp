package com.example.orders.repo;

import com.example.orders.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Native query with SELECT * -> pulls every column; also unbounded.
    @Query(value = "SELECT * FROM orders WHERE status = :status", nativeQuery = true)
    List<Order> findByStatusNative(@Param("status") String status);

    default Order findByIdOrThrow(Long id) {
        return findById(id).orElseThrow();
    }
}
