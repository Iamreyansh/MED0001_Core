package com.nammamedmate.order.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface ReorderAttemptLogStore {

  record ReorderAttemptLog(
      UUID id,
      UUID customerId,
      UUID sourceOrderId,
      UUID resultingCartId,
      boolean pharmacyChanged,
      int itemsRequested,
      int itemsAdded,
      int itemsExcluded,
      Instant createdAt) {}

  void insert(ReorderAttemptLog row);
}
