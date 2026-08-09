package com.nammamedmate.inventory.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.inventory.application.PurchaseGrnService.ListPage;
import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery.ProductPage;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListResult;
import org.junit.jupiter.api.Test;

class PortRecordDefaultsTest {

  @Test
  void nullListsBecomeEmpty() {
    assertThat(new ListResult(null, 0).rows()).isEmpty();
    assertThat(new ProductPage(null, 0, 1, 20).items()).isEmpty();
    assertThat(new ListPage(null, null).data()).isEmpty();
    assertThat(new DistributorStore.ListResult(null, 0).items()).isEmpty();
    assertThat(new DistributorSupplyItemStore.ListResult(null, 0).items()).isEmpty();
    assertThat(new DistributorSupplyItemStore.PriceCompareResult(null, 0).products()).isEmpty();
    assertThat(
            new DistributorSupplyItemStore.PriceProduct(java.util.UUID.randomUUID(), "n", "m", null)
                .offers())
        .isEmpty();
    assertThat(
            new com.nammamedmate.inventory.application.DistributorService.ListPage(null, null)
                .data())
        .isEmpty();
  }
}
