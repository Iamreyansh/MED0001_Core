package com.nammamedmate.integration.support;

import com.nammamedmate.integration.application.port.out.CashfreeBeneficiaryStore;
import com.nammamedmate.integration.application.port.out.CashfreePaymentRecordStore;
import com.nammamedmate.integration.application.port.out.CashfreePayoutRecordStore;
import com.nammamedmate.integration.application.port.out.GeocodeCacheStore;
import com.nammamedmate.integration.application.port.out.MapsApiCallLogStore;
import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStores {

  private InMemoryStores() {}

  public static final class MapsLogs implements MapsApiCallLogStore {
    private final List<MapsApiCallLog> logs = new ArrayList<>();

    @Override
    public synchronized void insert(MapsApiCallLog log) {
      logs.add(log);
    }

    @Override
    public synchronized BigDecimal sumEstimatedCostSince(Instant since) {
      return logs.stream()
          .filter(l -> !l.calledAt().isBefore(since))
          .map(MapsApiCallLog::estimatedCostRs)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public synchronized List<MapsApiCallLog> all() {
      return List.copyOf(logs);
    }

    public synchronized int size() {
      return logs.size();
    }
  }

  public static final class GeocodeCache implements GeocodeCacheStore {
    private final Map<String, GeocodeCacheEntry> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<GeocodeCacheEntry> findValid(String cacheKey, Instant now) {
      GeocodeCacheEntry e = byKey.get(cacheKey);
      if (e == null || !e.expiresAt().isAfter(now)) {
        return Optional.empty();
      }
      return Optional.of(e);
    }

    @Override
    public void upsert(GeocodeCacheEntry entry) {
      byKey.put(entry.cacheKey(), entry);
    }
  }

  public static final class Payments implements CashfreePaymentRecordStore {
    private final Map<UUID, CashfreePaymentRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(CashfreePaymentRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public void update(CashfreePaymentRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<CashfreePaymentRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<CashfreePaymentRecord> findByGatewayOrderId(String gatewayOrderId) {
      return byId.values().stream()
          .filter(r -> r.gatewayOrderId().equals(gatewayOrderId))
          .findFirst();
    }

    @Override
    public Optional<CashfreePaymentRecord> findByGatewayPaymentId(String gatewayPaymentId) {
      return byId.values().stream()
          .filter(
              r -> r.gatewayPaymentId() != null && r.gatewayPaymentId().equals(gatewayPaymentId))
          .findFirst();
    }

    public int size() {
      return byId.size();
    }
  }

  public static final class FundAccounts implements CashfreeBeneficiaryStore {
    private final Map<UUID, CashfreeBeneficiary> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(CashfreeBeneficiary account) {
      byId.put(account.id(), account);
    }

    @Override
    public void deactivate(UUID id) {
      CashfreeBeneficiary fa = byId.get(id);
      if (fa != null) {
        byId.put(
            id,
            new CashfreeBeneficiary(
                fa.id(),
                fa.entityType(),
                fa.entityId(),
                fa.cashfreeContactId(),
                fa.beneficiaryId(),
                fa.bankName(),
                fa.accountLast4(),
                fa.ifsc(),
                fa.accountHolderName(),
                false,
                fa.createdAt()));
      }
    }

    @Override
    public Optional<CashfreeBeneficiary> findActiveByEntity(String entityType, UUID entityId) {
      return byId.values().stream()
          .filter(
              a -> a.active() && a.entityType().equals(entityType) && a.entityId().equals(entityId))
          .findFirst();
    }

    @Override
    public Optional<CashfreeBeneficiary> findByBeneficiaryId(String beneficiaryId) {
      return byId.values().stream()
          .filter(a -> a.beneficiaryId().equals(beneficiaryId))
          .findFirst();
    }
  }

  public static final class Payouts implements CashfreePayoutRecordStore {
    private final Map<UUID, CashfreePayoutRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(CashfreePayoutRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public void update(CashfreePayoutRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<CashfreePayoutRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<CashfreePayoutRecord> findByCashfreexPayoutId(String payoutId) {
      return byId.values().stream()
          .filter(r -> r.cashfreeTransferId() != null && r.cashfreeTransferId().equals(payoutId))
          .findFirst();
    }

    @Override
    public Optional<CashfreePayoutRecord> findByReferenceId(String referenceId) {
      return byId.values().stream().filter(r -> r.referenceId().equals(referenceId)).findFirst();
    }

    @Override
    public List<CashfreePayoutRecord> findRetryEligible(Instant initiatedBefore, int limit) {
      List<CashfreePayoutRecord> out = new ArrayList<>();
      for (CashfreePayoutRecord r : byId.values()) {
        if ("failed".equals(r.status())
            && r.retryCount() == 0
            && !r.initiatedAt().isAfter(initiatedBefore)) {
          out.add(r);
          if (out.size() >= limit) {
            break;
          }
        }
      }
      return out;
    }
  }
}
