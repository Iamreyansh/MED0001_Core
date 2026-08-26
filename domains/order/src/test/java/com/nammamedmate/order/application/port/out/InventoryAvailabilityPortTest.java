package com.nammamedmate.order.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryAvailabilityPortTest {

  @Test
  void defaultsAreEmptyNoOps() {
    InventoryAvailabilityPort port = new InventoryAvailabilityPort() {};
    UUID id = UUID.randomUUID();
    assertThat(port.stocksMedicine(id, id)).isFalse();
    assertThat(port.findMedicine(id)).isEmpty();
    assertThat(port.checkAvailability(id, List.of(id))).isEmpty();
    assertThat(port.listVisibleProducts(id, null, null, 1, 20).total()).isZero();
    assertThat(port.medicineName(id)).isEmpty();
    port.reserveForOrder(id, id, List.of());
    port.deductForOrder(id);
    port.releaseForOrder(id);
  }
}
