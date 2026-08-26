package com.nammamedmate.customer.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMethodStore {

  List<PaymentMethodRecord> listByCustomer(UUID customerId);

  int countByCustomerAndType(UUID customerId, String type);

  Optional<PaymentMethodRecord> findByIdForCustomer(UUID methodId, UUID customerId);

  List<PaymentMethodRecord> listByCustomerAndType(UUID customerId, String type);

  PaymentMethodRecord insert(PaymentMethodRecord method);

  void softDelete(UUID methodId, UUID customerId, Instant deletedAt);

  Optional<PaymentMethodRecord> findByIdempotencyKey(String idempotencyKey);

  void clearDefaultFlags(UUID customerId);

  void setDefault(UUID methodId, UUID customerId);

  Optional<UUID> findDefaultMethodId(UUID customerId);

  record PaymentMethodRecord(
      UUID id,
      UUID customerId,
      String type,
      boolean isDefault,
      String nickname,
      String upiIdEncrypted,
      String upiHandle,
      String cashfreeTokenEncrypted,
      String cardLast4,
      String cardNetwork,
      String cardType,
      String idempotencyKey,
      Instant createdAt,
      Instant deletedAt) {}
}
