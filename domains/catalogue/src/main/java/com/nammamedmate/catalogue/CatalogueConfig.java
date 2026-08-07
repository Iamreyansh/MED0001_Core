package com.nammamedmate.catalogue;

import com.nammamedmate.catalogue.adapter.out.medicine.StubOrderDemandClient;
import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogueConfig {

  @Bean
  @ConditionalOnMissingBean(OrderDemandPort.class)
  OrderDemandPort orderDemandPort() {
    return new StubOrderDemandClient();
  }
}
