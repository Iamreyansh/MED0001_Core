package com.nammamedmate.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerConfigTest {

  @Test
  void noActiveOrdersPort_returnsFalse() {
    CustomerConfig config = new CustomerConfig();
    assertThat(config.noActiveOrdersPort().hasActiveOrders(UUID.randomUUID())).isFalse();
  }
}
