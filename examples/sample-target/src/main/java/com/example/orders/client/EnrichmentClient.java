package com.example.orders.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class EnrichmentClient {

    private final RestTemplate restTemplate = new RestTemplate();

    // Outbound HTTP call; when invoked per-row it dominates latency.
    public int fetchRating(Long customerId) {
        Integer rating = restTemplate.getForObject(
                "http://ratings-service/ratings/" + customerId, Integer.class);
        return rating == null ? 0 : rating;
    }
}
