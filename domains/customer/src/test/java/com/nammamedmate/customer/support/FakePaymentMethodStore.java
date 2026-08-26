package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.PaymentMethodStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link PaymentMethodStore} for unit tests. */
public final class FakePaymentMethodStore implements PaymentMethodStore {

  private final Map<UUID, PaymentMethodRecord> methods = new ConcurrentHashMap<>();

  public void seed(PaymentMethodRecord method) {
    methods.put(method.id(), method);
  }

  public void clear() {
    methods.clear();
  }

  @Override
  public List<PaymentMethodRecord> listByCustomer(UUID customerId) {
    List<PaymentMethodRecord> rows = new ArrayList<>();
    for (PaymentMethodRecord m : methods.values()) {
      if (m.customerId().equals(customerId) && m.deletedAt() == null) {
        rows.add(m);
      }
    }
    rows.sort(
        Comparator.comparing(PaymentMethodRecord::isDefault)
            .reversed()
            .thenComparing(PaymentMethodRecord::createdAt));
    return rows;
  }

  @Override
  public int countByCustomerAndType(UUID customerId, String type) {
    return (int)
        methods.values().stream()
            .filter(
                m ->
                    m.customerId().equals(customerId)
                        && m.deletedAt() == null
                        && m.type().equals(type))
            .count();
  }

  @Override
  public Optional<PaymentMethodRecord> findByIdForCustomer(UUID methodId, UUID customerId) {
    PaymentMethodRecord m = methods.get(methodId);
    if (m == null || m.deletedAt() != null || !m.customerId().equals(customerId)) {
      return Optional.empty();
    }
    return Optional.of(m);
  }

  @Override
  public List<PaymentMethodRecord> listByCustomerAndType(UUID customerId, String type) {
    List<PaymentMethodRecord> rows = new ArrayList<>();
    for (PaymentMethodRecord m : methods.values()) {
      if (m.customerId().equals(customerId) && m.deletedAt() == null && m.type().equals(type)) {
        rows.add(m);
      }
    }
    return rows;
  }

  @Override
  public Optional<PaymentMethodRecord> findByIdempotencyKey(String idempotencyKey) {
    return methods.values().stream()
        .filter(
            m ->
                m.deletedAt() == null
                    && idempotencyKey != null
                    && idempotencyKey.equals(m.idempotencyKey()))
        .findFirst();
  }

  @Override
  public PaymentMethodRecord insert(PaymentMethodRecord method) {
    methods.put(method.id(), method);
    return method;
  }

  @Override
  public void softDelete(UUID methodId, UUID customerId, Instant deletedAt) {
    PaymentMethodRecord m = methods.get(methodId);
    if (m == null || m.deletedAt() != null || !m.customerId().equals(customerId)) {
      return;
    }
    methods.put(
        methodId,
        new PaymentMethodRecord(
            m.id(),
            m.customerId(),
            m.type(),
            false,
            m.nickname(),
            m.upiIdEncrypted(),
            m.upiHandle(),
            m.cashfreeTokenEncrypted(),
            m.cardLast4(),
            m.cardNetwork(),
            m.cardType(),
            m.idempotencyKey(),
            m.createdAt(),
            deletedAt));
  }

  @Override
  public void clearDefaultFlags(UUID customerId) {
    for (PaymentMethodRecord m : List.copyOf(methods.values())) {
      if (m.customerId().equals(customerId) && m.deletedAt() == null && m.isDefault()) {
        methods.put(
            m.id(),
            new PaymentMethodRecord(
                m.id(),
                m.customerId(),
                m.type(),
                false,
                m.nickname(),
                m.upiIdEncrypted(),
                m.upiHandle(),
                m.cashfreeTokenEncrypted(),
                m.cardLast4(),
                m.cardNetwork(),
                m.cardType(),
                m.idempotencyKey(),
                m.createdAt(),
                null));
      }
    }
  }

  @Override
  public void setDefault(UUID methodId, UUID customerId) {
    PaymentMethodRecord m = methods.get(methodId);
    if (m == null || m.deletedAt() != null || !m.customerId().equals(customerId)) {
      return;
    }
    methods.put(
        methodId,
        new PaymentMethodRecord(
            m.id(),
            m.customerId(),
            m.type(),
            true,
            m.nickname(),
            m.upiIdEncrypted(),
            m.upiHandle(),
            m.cashfreeTokenEncrypted(),
            m.cardLast4(),
            m.cardNetwork(),
            m.cardType(),
            m.idempotencyKey(),
            m.createdAt(),
            null));
  }

  @Override
  public Optional<UUID> findDefaultMethodId(UUID customerId) {
    return methods.values().stream()
        .filter(m -> m.customerId().equals(customerId) && m.deletedAt() == null && m.isDefault())
        .map(PaymentMethodRecord::id)
        .findFirst();
  }
}
