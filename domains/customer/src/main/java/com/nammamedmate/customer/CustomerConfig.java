package com.nammamedmate.customer;

import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerConfig {

  @Bean
  @ConditionalOnMissingBean(ActiveOrdersPort.class)
  ActiveOrdersPort noActiveOrdersPort() {
    // ponytail: orders domain (EPIC-010) not wired yet — deletion never blocked by active orders
    // until order port is provided; upgrade: implement ActiveOrdersPort in domains/order
    return customerId -> false;
  }
}
