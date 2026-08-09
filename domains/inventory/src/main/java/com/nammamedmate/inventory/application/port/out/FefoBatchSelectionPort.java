package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.ProductBatch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FEFO batch selection for POS cart auto-add. Does not deduct stock (EPIC-007 owns sales).
 *
 * <p>Expired batches ({@code expiry_date < today}) and inactive/zero-qty batches are excluded.
 */
public interface FefoBatchSelectionPort {

  /** Earliest-expiring eligible batch for a product, or empty if none. */
  Optional<ProductBatch> selectFefoBatch(UUID pharmacyId, UUID productId);

  /** All POS-eligible batches for a product in FEFO order. */
  List<ProductBatch> listPosEligibleBatches(UUID pharmacyId, UUID productId);
}
