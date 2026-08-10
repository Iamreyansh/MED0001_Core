package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.util.List;
import java.util.UUID;

public interface OrderLinesPort {

  void replaceOrderLines(UUID orderId, List<ApprovedMedicine> medicines);
}
