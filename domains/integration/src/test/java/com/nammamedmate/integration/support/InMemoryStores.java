package com.nammamedmate.integration.support;

import com.nammamedmate.integration.application.port.out.AccountingIntegrationStore;
import com.nammamedmate.integration.application.port.out.AccountingSyncJobStore;
import com.nammamedmate.integration.application.port.out.CommunicationChannelConfigStore;
import com.nammamedmate.integration.application.port.out.CommunicationConfigAuditStore;
import com.nammamedmate.integration.application.port.out.CommunicationCostDailyStore;
import com.nammamedmate.integration.application.port.out.CommunicationSecretsStore;
import com.nammamedmate.integration.application.port.out.EinvoiceApiCallLogStore;
import com.nammamedmate.integration.application.port.out.EinvoiceIrnRecordStore;
import com.nammamedmate.integration.application.port.out.GeocodeCacheStore;
import com.nammamedmate.integration.application.port.out.GovernmentApiCallLogStore;
import com.nammamedmate.integration.application.port.out.GovernmentVerificationCacheStore;
import com.nammamedmate.integration.application.port.out.MapsApiCallLogStore;
import com.nammamedmate.integration.application.port.out.PharmacyEinvoiceFlagStore;
import com.nammamedmate.integration.application.port.out.RazorpayPaymentRecordStore;
import com.nammamedmate.integration.application.port.out.RazorpayXFundAccountStore;
import com.nammamedmate.integration.application.port.out.RazorpayXPayoutRecordStore;
import com.nammamedmate.integration.domain.AccountingIntegration;
import com.nammamedmate.integration.domain.AccountingSyncJob;
import com.nammamedmate.integration.domain.AccountingSyncStatuses;
import com.nammamedmate.integration.domain.CommunicationChannelConfig;
import com.nammamedmate.integration.domain.CommunicationChannels;
import com.nammamedmate.integration.domain.CommunicationConfigAudit;
import com.nammamedmate.integration.domain.CommunicationCostDaily;
import com.nammamedmate.integration.domain.CommunicationProviders;
import com.nammamedmate.integration.domain.CommunicationStatuses;
import com.nammamedmate.integration.domain.EinvoiceApiCallLog;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.domain.GovernmentApiCallLog;
import com.nammamedmate.integration.domain.GovernmentVerificationCacheEntry;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryStores {

  private InMemoryStores() {}

  public static final class Payments implements RazorpayPaymentRecordStore {
    private final Map<UUID, RazorpayPaymentRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RazorpayPaymentRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public void update(RazorpayPaymentRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<RazorpayPaymentRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RazorpayPaymentRecord> findByRazorpayOrderId(String razorpayOrderId) {
      return byId.values().stream()
          .filter(r -> r.razorpayOrderId().equals(razorpayOrderId))
          .findFirst();
    }

    @Override
    public Optional<RazorpayPaymentRecord> findByRazorpayPaymentId(String razorpayPaymentId) {
      return byId.values().stream()
          .filter(
              r -> r.razorpayPaymentId() != null && r.razorpayPaymentId().equals(razorpayPaymentId))
          .findFirst();
    }

    public int size() {
      return byId.size();
    }
  }

  public static final class FundAccounts implements RazorpayXFundAccountStore {
    private final Map<UUID, RazorpayXFundAccount> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RazorpayXFundAccount account) {
      byId.put(account.id(), account);
    }

    @Override
    public void deactivate(UUID id) {
      RazorpayXFundAccount fa = byId.get(id);
      if (fa != null) {
        byId.put(
            id,
            new RazorpayXFundAccount(
                fa.id(),
                fa.entityType(),
                fa.entityId(),
                fa.razorpayxContactId(),
                fa.fundAccountId(),
                fa.bankName(),
                fa.accountLast4(),
                fa.ifsc(),
                fa.accountHolderName(),
                false,
                fa.createdAt()));
      }
    }

    @Override
    public Optional<RazorpayXFundAccount> findActiveByEntity(String entityType, UUID entityId) {
      return byId.values().stream()
          .filter(
              a -> a.active() && a.entityType().equals(entityType) && a.entityId().equals(entityId))
          .findFirst();
    }

    @Override
    public Optional<RazorpayXFundAccount> findByFundAccountId(String fundAccountId) {
      return byId.values().stream()
          .filter(a -> a.fundAccountId().equals(fundAccountId))
          .findFirst();
    }
  }

  public static final class Payouts implements RazorpayXPayoutRecordStore {
    private final Map<UUID, RazorpayXPayoutRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RazorpayXPayoutRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public void update(RazorpayXPayoutRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<RazorpayXPayoutRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RazorpayXPayoutRecord> findByRazorpayxPayoutId(String payoutId) {
      return byId.values().stream()
          .filter(r -> r.razorpayxPayoutId() != null && r.razorpayxPayoutId().equals(payoutId))
          .findFirst();
    }

    @Override
    public Optional<RazorpayXPayoutRecord> findByReferenceId(String referenceId) {
      return byId.values().stream().filter(r -> r.referenceId().equals(referenceId)).findFirst();
    }

    @Override
    public List<RazorpayXPayoutRecord> findRetryEligible(Instant initiatedBefore, int limit) {
      List<RazorpayXPayoutRecord> out = new ArrayList<>();
      for (RazorpayXPayoutRecord r : byId.values()) {
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

  public static final class GovCache implements GovernmentVerificationCacheStore {
    private final Map<String, GovernmentVerificationCacheEntry> byKey = new ConcurrentHashMap<>();

    private static String key(String type, String identifier, String state) {
      return type + "|" + identifier + "|" + (state == null ? "" : state);
    }

    @Override
    public Optional<GovernmentVerificationCacheEntry> findValid(
        String verificationType, String identifier, String state, Instant now) {
      GovernmentVerificationCacheEntry e = byKey.get(key(verificationType, identifier, state));
      if (e == null || !e.expiresAt().isAfter(now)) {
        return Optional.empty();
      }
      return Optional.of(e);
    }

    @Override
    public void upsert(GovernmentVerificationCacheEntry entry) {
      byKey.put(key(entry.verificationType(), entry.identifier(), entry.state()), entry);
    }
  }

  public static final class GovLogs implements GovernmentApiCallLogStore {
    private final List<GovernmentApiCallLog> logs = new ArrayList<>();

    @Override
    public synchronized void insert(GovernmentApiCallLog log) {
      logs.add(log);
    }

    public synchronized List<GovernmentApiCallLog> all() {
      return List.copyOf(logs);
    }
  }

  public static final class EinvoiceRecords implements EinvoiceIrnRecordStore {
    private final Map<UUID, EinvoiceIrnRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(EinvoiceIrnRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public void update(EinvoiceIrnRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<EinvoiceIrnRecord> findByIrn(String irn) {
      return byId.values().stream().filter(r -> r.irn().equals(irn)).findFirst();
    }

    @Override
    public Optional<EinvoiceIrnRecord> findByDocumentKey(
        String sellerGstin,
        String buyerGstin,
        String documentType,
        String financialYear,
        String invoiceNumber) {
      return byId.values().stream()
          .filter(
              r ->
                  r.sellerGstin().equals(sellerGstin)
                      && r.buyerGstin().equals(buyerGstin)
                      && r.documentType().equals(documentType)
                      && r.financialYear().equals(financialYear)
                      && r.invoiceNumber().equals(invoiceNumber))
          .findFirst();
    }

    @Override
    public Optional<EinvoiceIrnRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    public int size() {
      return byId.size();
    }
  }

  public static final class EinvoiceLogs implements EinvoiceApiCallLogStore {
    private final List<EinvoiceApiCallLog> logs = new ArrayList<>();

    @Override
    public synchronized void insert(EinvoiceApiCallLog log) {
      logs.add(log);
    }

    public synchronized List<EinvoiceApiCallLog> all() {
      return List.copyOf(logs);
    }

    public synchronized int size() {
      return logs.size();
    }
  }

  public static final class PharmacyFlags implements PharmacyEinvoiceFlagStore {
    private final Map<UUID, Boolean> flags = new ConcurrentHashMap<>();

    public void put(UUID pharmacyId, boolean enabled) {
      flags.put(pharmacyId, enabled);
    }

    @Override
    public Optional<Boolean> findEInvoicingEnabled(UUID pharmacyId) {
      return Optional.ofNullable(flags.get(pharmacyId));
    }
  }

  public static final class AccountingIntegrations implements AccountingIntegrationStore {
    private final Map<UUID, AccountingIntegration> byPharmacy = new ConcurrentHashMap<>();

    @Override
    public Optional<AccountingIntegration> findByPharmacyId(UUID pharmacyId) {
      return Optional.ofNullable(byPharmacy.get(pharmacyId));
    }

    @Override
    public void upsert(AccountingIntegration integration) {
      byPharmacy.put(integration.pharmacyId(), integration);
    }

    @Override
    public List<AccountingIntegration> findDueAutoSync(Instant now, int limit) {
      return byPharmacy.values().stream()
          .filter(AccountingIntegration::autoSyncEnabled)
          .filter(c -> c.nextSyncAt() != null && !c.nextSyncAt().isAfter(now))
          .sorted(Comparator.comparing(AccountingIntegration::nextSyncAt))
          .limit(limit)
          .toList();
    }
  }

  public static final class AccountingJobs implements AccountingSyncJobStore {
    private final Map<UUID, AccountingSyncJob> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(AccountingSyncJob job) {
      byId.put(job.id(), job);
    }

    @Override
    public void update(AccountingSyncJob job) {
      byId.put(job.id(), job);
    }

    @Override
    public Optional<AccountingSyncJob> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean hasActiveJob(UUID pharmacyId) {
      return byId.values().stream()
          .anyMatch(
              j ->
                  j.pharmacyId().equals(pharmacyId) && AccountingSyncStatuses.isActive(j.status()));
    }

    @Override
    public List<AccountingSyncJob> findQueued(int limit) {
      return byId.values().stream()
          .filter(j -> AccountingSyncStatuses.QUEUED.equals(j.status()))
          .sorted(Comparator.comparing(AccountingSyncJob::queuedAt))
          .limit(limit)
          .toList();
    }
  }

  public static final class CommsConfigs implements CommunicationChannelConfigStore {
    private final Map<String, CommunicationChannelConfig> byChannel = new ConcurrentHashMap<>();

    public CommsConfigs seedDefaults(Instant now) {
      put(
          new CommunicationChannelConfig(
              CommunicationChannels.PUSH,
              true,
              CommunicationProviders.FIREBASE_FCM,
              null,
              "medmate/comms/push",
              100000,
              0,
              CommunicationStatuses.HEALTHY,
              now,
              null,
              now));
      put(
          new CommunicationChannelConfig(
              CommunicationChannels.SMS,
              true,
              CommunicationProviders.MSG91,
              CommunicationProviders.TWILIO,
              "medmate/comms/sms",
              50000,
              0,
              CommunicationStatuses.HEALTHY,
              now,
              null,
              now));
      put(
          new CommunicationChannelConfig(
              CommunicationChannels.WHATSAPP,
              true,
              CommunicationProviders.META_CLOUD_API,
              null,
              "medmate/comms/whatsapp",
              20000,
              0,
              CommunicationStatuses.HEALTHY,
              now,
              null,
              now));
      put(
          new CommunicationChannelConfig(
              CommunicationChannels.EMAIL,
              true,
              CommunicationProviders.SENDGRID,
              CommunicationProviders.AWS_SES,
              "medmate/comms/email",
              100000,
              0,
              CommunicationStatuses.HEALTHY,
              now,
              null,
              now));
      return this;
    }

    public void put(CommunicationChannelConfig config) {
      byChannel.put(config.channel(), config);
    }

    @Override
    public List<CommunicationChannelConfig> findAll() {
      return byChannel.values().stream()
          .sorted(Comparator.comparing(CommunicationChannelConfig::channel))
          .toList();
    }

    @Override
    public Optional<CommunicationChannelConfig> findByChannel(String channel) {
      return Optional.ofNullable(byChannel.get(channel));
    }

    @Override
    public void update(CommunicationChannelConfig config) {
      byChannel.put(config.channel(), config);
    }

    @Override
    public void resetAllDailySentCounts() {
      byChannel.replaceAll(
          (ch, c) ->
              new CommunicationChannelConfig(
                  c.channel(),
                  c.enabled(),
                  c.provider(),
                  c.fallbackProvider(),
                  c.secretsManagerKey(),
                  c.dailySendLimit(),
                  0,
                  c.currentStatus(),
                  c.lastHealthCheckAt(),
                  c.updatedBy(),
                  c.updatedAt()));
    }
  }

  public static final class CommsCosts implements CommunicationCostDailyStore {
    private final Map<String, CommunicationCostDaily> byKey = new ConcurrentHashMap<>();

    private static String key(LocalDate date, String channel, String provider) {
      return date + "|" + channel + "|" + provider;
    }

    @Override
    public Optional<CommunicationCostDaily> find(LocalDate date, String channel, String provider) {
      return Optional.ofNullable(byKey.get(key(date, channel, provider)));
    }

    @Override
    public List<CommunicationCostDaily> findByDate(LocalDate date) {
      return byKey.values().stream().filter(r -> r.date().equals(date)).toList();
    }

    @Override
    public List<CommunicationCostDaily> findByChannelAndDateRange(
        String channel, LocalDate fromInclusive, LocalDate toInclusive) {
      return byKey.values().stream()
          .filter(
              r ->
                  r.channel().equals(channel)
                      && !r.date().isBefore(fromInclusive)
                      && !r.date().isAfter(toInclusive))
          .toList();
    }

    @Override
    public void upsertIncrement(
        LocalDate date,
        String channel,
        String provider,
        int sentDelta,
        int deliveredDelta,
        int fallbackDelta,
        BigDecimal costDelta) {
      String k = key(date, channel, provider);
      byKey.compute(
          k,
          (ignored, existing) -> {
            if (existing == null) {
              return new CommunicationCostDaily(
                  UUID.randomUUID(),
                  date,
                  channel,
                  provider,
                  sentDelta,
                  deliveredDelta,
                  fallbackDelta,
                  costDelta,
                  Instant.parse("2026-07-24T10:00:00Z"));
            }
            return new CommunicationCostDaily(
                existing.id(),
                existing.date(),
                existing.channel(),
                existing.provider(),
                existing.sentCount() + sentDelta,
                existing.deliveredCount() + deliveredDelta,
                existing.fallbackSentCount() + fallbackDelta,
                existing.costRs().add(costDelta),
                existing.createdAt());
          });
    }
  }

  public static final class CommsAudits implements CommunicationConfigAuditStore {
    private final List<CommunicationConfigAudit> rows = new ArrayList<>();

    @Override
    public synchronized void insert(CommunicationConfigAudit audit) {
      rows.add(audit);
    }

    @Override
    public synchronized List<CommunicationConfigAudit> findByChannel(String channel) {
      return rows.stream().filter(a -> a.channel().equals(channel)).toList();
    }

    public synchronized List<CommunicationConfigAudit> all() {
      return List.copyOf(rows);
    }
  }

  public static final class CommsSecrets implements CommunicationSecretsStore {
    private final Map<String, Map<String, String>> secrets = new ConcurrentHashMap<>();

    public CommsSecrets() {
      secrets.put("medmate/comms/push", Map.of("api_key", "fcm-stub-key-0001"));
      secrets.put("medmate/comms/sms", Map.of("api_key", "msg91-stub-key", "sender_id", "NMMATE"));
      secrets.put("medmate/comms/whatsapp", Map.of("api_key", "meta-stub-token"));
      secrets.put("medmate/comms/email", Map.of("api_key", "sg-stub-key-0001"));
    }

    @Override
    public Optional<Map<String, String>> get(String secretsManagerKey) {
      Map<String, String> value = secrets.get(secretsManagerKey);
      return value == null ? Optional.empty() : Optional.of(Map.copyOf(value));
    }

    @Override
    public void put(String secretsManagerKey, Map<String, String> credentials) {
      secrets.put(secretsManagerKey, Map.copyOf(credentials));
    }
  }
}
