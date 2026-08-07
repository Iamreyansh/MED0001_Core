package com.nammamedmate.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogueConfigTest {

  @Test
  void stubOrderDemandPort() {
    CatalogueConfig config = new CatalogueConfig();
    UUID id = UUID.randomUUID();
    assertThat(config.orderDemandPort().trailing30DayOrderCount(id)).isZero();
  }
}
