package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Opening stock + batch for schedule register running balance (inventory bridge). */
public interface InventoryBatchPort {

  record OpeningStock(String batchNo, int quantity) {}

  Optional<OpeningStock> findOpeningStock(UUID pharmacyId, String drugName);
}
