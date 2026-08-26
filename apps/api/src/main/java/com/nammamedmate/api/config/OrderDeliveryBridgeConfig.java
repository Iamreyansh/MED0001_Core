package com.nammamedmate.api.config;

import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.rider.application.port.out.OrderDeliveryConfirmPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Rider deliver → canonical OrderLifecycleService (D12 invoice + order.delivered). */
@Configuration
public class OrderDeliveryBridgeConfig {

  @Bean
  @Primary
  OrderDeliveryConfirmPort orderDeliveryConfirmPort(OrderLifecycleService lifecycle) {
    return lifecycle::confirmRiderDelivery;
  }
}
