package com.nammamedmate.messaging;

import java.util.Optional;

/** Persist-before-I/O ledger for payouts, refunds, and gateway charges. */
public interface ProviderOperationStore {

  record Operation(String operationType, String idempotencyKey, String providerRef, String status) {
    public boolean hasProviderRef() {
      return providerRef != null && !providerRef.isBlank();
    }

    public boolean terminalSuccess() {
      return "SUCCEEDED".equals(status) || "SENT".equals(status);
    }
  }

  Operation ensurePending(String operationType, String idempotencyKey, String provider);

  Optional<Operation> find(String operationType, String idempotencyKey);

  void markSent(String operationType, String idempotencyKey, String providerRef);

  void markSucceeded(String operationType, String idempotencyKey, String providerRef);

  void markFailed(String operationType, String idempotencyKey, String error);

  void markAmbiguous(String operationType, String idempotencyKey, String providerRef, String error);
}
