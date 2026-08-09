package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.application.port.out.FefoBatchSelectionPort;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.ProductBatch;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FefoBatchSelectionService implements FefoBatchSelectionPort {

  private final ProductBatchStore store;
  private final Clock clock;

  public FefoBatchSelectionService(ProductBatchStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public Optional<ProductBatch> selectFefoBatch(UUID pharmacyId, UUID productId) {
    List<ProductBatch> eligible = listPosEligibleBatches(pharmacyId, productId);
    return eligible.stream().findFirst();
  }

  @Override
  public List<ProductBatch> listPosEligibleBatches(UUID pharmacyId, UUID productId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return store.listFefoEligible(pharmacyId, productId, today);
  }
}
