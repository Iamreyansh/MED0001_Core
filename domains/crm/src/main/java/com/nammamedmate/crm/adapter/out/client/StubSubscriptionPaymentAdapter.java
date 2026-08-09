package com.nammamedmate.crm.adapter.out.client;

import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Succeeds by default; accounts in {@link #failingAccounts} throw PAYMENT_FAILED. */
@Component
public class StubSubscriptionPaymentAdapter implements SubscriptionPaymentPort {

  private final Set<UUID> failingAccounts = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, UUID> chargedByKey = new ConcurrentHashMap<>();

  public void failNextFor(UUID accountId) {
    failingAccounts.add(accountId);
  }

  public void clearFailures() {
    failingAccounts.clear();
  }

  @Override
  public UUID charge(UUID accountId, long amountPaise, String description, String idempotencyKey) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      return chargedByKey.computeIfAbsent(
          idempotencyKey, ignored -> chargeOnce(accountId, amountPaise));
    }
    return chargeOnce(accountId, amountPaise);
  }

  private UUID chargeOnce(UUID accountId, long amountPaise) {
    if (accountId != null && failingAccounts.remove(accountId)) {
      throw new AppException("PAYMENT_FAILED", "Payment initiation failed", 402);
    }
    if (amountPaise < 0) {
      throw new AppException("PAYMENT_FAILED", "Payment initiation failed", 402);
    }
    return Ids.newId();
  }
}
