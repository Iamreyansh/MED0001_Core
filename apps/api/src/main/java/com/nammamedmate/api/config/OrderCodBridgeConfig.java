package com.nammamedmate.api.config;

import com.nammamedmate.order.application.port.out.CodCollectionPort;
import com.nammamedmate.rider.application.CodReconciliationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridge: order {@link CodCollectionPort} → rider {@link CodReconciliationService}
 * (EPIC-011/STORY-007).
 */
@Configuration
public class OrderCodBridgeConfig {

  @Bean
  @Primary
  CodCollectionPort riderCodCollectionPort(CodReconciliationService cod) {
    return (UUID riderId, UUID orderId, long amountPaise, Instant collectedAt) ->
        cod.recordCollection(riderId, orderId, amountPaise, collectedAt);
  }
}
