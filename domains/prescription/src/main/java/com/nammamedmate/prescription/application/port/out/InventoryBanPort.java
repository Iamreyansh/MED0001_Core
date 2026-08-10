package com.nammamedmate.prescription.application.port.out;

import java.util.List;
import java.util.UUID;

/** Platform-wide ban of inventory batches matching drug name + batch number (drug recall). */
public interface InventoryBanPort {

  record BanResult(int batchesBanned, List<UUID> pharmacyIds) {
    public BanResult {
      pharmacyIds = pharmacyIds == null ? List.of() : List.copyOf(pharmacyIds);
    }
  }

  BanResult banByDrugNameAndBatch(String drugName, String batchNo);
}
