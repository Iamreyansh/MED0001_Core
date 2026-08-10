package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.util.List;
import java.util.UUID;

public interface PosDispensePort {

  boolean available();

  UUID pushToBillingCart(UUID pharmacyId, UUID staffId, List<ApprovedMedicine> medicines);

  UUID createSaleRecord(
      UUID pharmacyId, UUID staffId, UUID orderId, List<ApprovedMedicine> medicines);
}
