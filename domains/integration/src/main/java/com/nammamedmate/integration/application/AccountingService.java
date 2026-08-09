package com.nammamedmate.integration.application;

import com.nammamedmate.integration.application.port.out.AccountingDataPort;
import com.nammamedmate.integration.application.port.out.AccountingExportObjectStore;
import com.nammamedmate.integration.application.port.out.AccountingIntegrationStore;
import com.nammamedmate.integration.application.port.out.AccountingPlanPort;
import com.nammamedmate.integration.application.port.out.AccountingSyncJobStore;
import com.nammamedmate.integration.application.port.out.ZohoBooksClientPort;
import com.nammamedmate.integration.domain.AccountingApiKeyStatuses;
import com.nammamedmate.integration.domain.AccountingIntegration;
import com.nammamedmate.integration.domain.AccountingSyncFrequencies;
import com.nammamedmate.integration.domain.AccountingSyncJob;
import com.nammamedmate.integration.domain.AccountingSyncStatuses;
import com.nammamedmate.integration.domain.AccountingSyncTypes;
import com.nammamedmate.integration.domain.AccountingSystems;
import com.nammamedmate.integration.domain.AccountingTriggeredBy;
import com.nammamedmate.integration.domain.AccountingVoucher;
import com.nammamedmate.integration.domain.NextSyncAtCalculator;
import com.nammamedmate.integration.domain.TallyXmlBuilder;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AccountingService {

  private static final Duration DOWNLOAD_TTL = Duration.ofDays(7);
  private static final Duration TOKEN_REFRESH_SKEW = Duration.ofMinutes(5);
  private static final String TALLY_IMPORT_INSTRUCTIONS =
      "Open Tally > Gateway of Tally > Import Data > Vouchers. Select the downloaded XML file.";

  private final AccountingIntegrationStore integrations;
  private final AccountingSyncJobStore jobs;
  private final AccountingDataPort data;
  private final ZohoBooksClientPort zoho;
  private final AccountingExportObjectStore exports;
  private final AccountingPlanPort planGate;
  private final AesGcmCipher cipher;
  private final Clock clock;

  @Autowired
  public AccountingService(
      AccountingIntegrationStore integrations,
      AccountingSyncJobStore jobs,
      AccountingDataPort data,
      ZohoBooksClientPort zoho,
      AccountingExportObjectStore exports,
      AccountingPlanPort planGate,
      @Autowired(required = false) @Qualifier("accountingTokenCipher") AesGcmCipher cipher,
      Clock clock) {
    this.integrations = integrations;
    this.jobs = jobs;
    this.data = data;
    this.zoho = zoho;
    this.exports = exports;
    this.planGate = planGate;
    this.cipher = cipher;
    this.clock = clock;
  }

  public Map<String, Object> triggerSync(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String accountingSystem,
      String syncType,
      LocalDate periodFrom,
      LocalDate periodTo) {
    requireOwner(principal);
    UUID ph = resolvePharmacy(principal, pharmacyId);
    requirePlan(ph);
    if (!AccountingSystems.isValid(accountingSystem)) {
      throw new AppException(
          "VALIDATION_ERROR", "accounting_system must be TALLY or ZOHO_BOOKS", 422);
    }
    if (!AccountingSyncTypes.isValid(syncType)) {
      throw new AppException("VALIDATION_ERROR", "Invalid sync_type", 422);
    }
    if (periodFrom == null || periodTo == null || periodFrom.isAfter(periodTo)) {
      throw new AppException("INVALID_PERIOD", "period_from must be on or before period_to", 422);
    }
    AccountingIntegration config =
        integrations
            .findByPharmacyId(ph)
            .filter(c -> AccountingApiKeyStatuses.CONNECTED.equals(c.apiKeyStatus()))
            .orElseThrow(
                () ->
                    new AppException(
                        "ACCOUNTING_NOT_CONFIGURED", "No accounting system connected", 422));
    if (!config.accountingSystem().equals(accountingSystem)) {
      throw new AppException(
          "ACCOUNTING_NOT_CONFIGURED",
          "Requested accounting_system does not match connected system",
          422);
    }
    if (jobs.hasActiveJob(ph)) {
      throw new AppException("SYNC_IN_PROGRESS", "Another sync job is already running", 429);
    }
    List<AccountingVoucher> vouchers = loadVouchers(ph, syncType, periodFrom, periodTo);
    Instant now = Instant.now(clock);
    UUID jobId = UUID.randomUUID();
    AccountingSyncJob job =
        new AccountingSyncJob(
            jobId,
            ph,
            accountingSystem,
            syncType,
            periodFrom,
            periodTo,
            AccountingSyncStatuses.QUEUED,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            now,
            null,
            null);
    jobs.insert(job);
    // ponytail: process in-process immediately (no SQS worker for accounting yet).
    processJob(jobId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("accounting_system", accountingSystem);
    data.put("sync_type", syncType);
    data.put("period_from", periodFrom.toString());
    data.put("period_to", periodTo.toString());
    data.put("status", AccountingSyncStatuses.QUEUED);
    data.put("estimated_records", vouchers.size());
    data.put("queued_at", now.toString());
    return data;
  }

  public Map<String, Object> syncStatus(MedmatePrincipal principal, UUID jobId) {
    requireOwnerOrOps(principal);
    AccountingSyncJob job =
        jobs.findById(jobId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Sync job not found", 404));
    if (principal.role() == AuthRole.PHARMACY_OWNER) {
      if (principal.pharmacyId() == null || !principal.pharmacyId().equals(job.pharmacyId())) {
        throw new AppException("FORBIDDEN", "Job belongs to another pharmacy", 403);
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id().toString());
    data.put("accounting_system", job.accountingSystem());
    data.put("sync_type", job.syncType());
    data.put("status", job.status());
    data.put("records_processed", job.recordsProcessed());
    data.put("records_synced", job.recordsSynced());
    data.put("records_failed", job.recordsFailed());
    data.put("errors", job.errors());
    data.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
    data.put("completed_at", job.completedAt() == null ? null : job.completedAt().toString());
    return data;
  }

  public Map<String, Object> getConfig(MedmatePrincipal principal) {
    requireOwner(principal);
    UUID ph = principal.pharmacyId();
    requirePlan(ph);
    Optional<AccountingIntegration> opt = integrations.findByPharmacyId(ph);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", ph.toString());
    if (opt.isEmpty() || AccountingApiKeyStatuses.DISCONNECTED.equals(opt.get().apiKeyStatus())) {
      data.put("connected_system", null);
      data.put("zoho_organization_id", null);
      data.put("zoho_organization_name", null);
      data.put("api_key_status", AccountingApiKeyStatuses.DISCONNECTED);
      data.put("last_sync_at", null);
      data.put("last_sync_status", null);
      data.put("auto_sync_enabled", false);
      data.put("sync_frequency", null);
      data.put("tally_xml_available", false);
      return data;
    }
    AccountingIntegration c = opt.get();
    data.put("connected_system", c.accountingSystem());
    data.put("zoho_organization_id", c.zohoOrganizationId());
    data.put("zoho_organization_name", c.zohoOrganizationName());
    data.put("api_key_status", c.apiKeyStatus());
    data.put("last_sync_at", c.lastSyncAt() == null ? null : c.lastSyncAt().toString());
    data.put("last_sync_status", c.lastSyncStatus());
    data.put("auto_sync_enabled", c.autoSyncEnabled());
    data.put("sync_frequency", c.syncFrequency());
    data.put("tally_xml_available", AccountingSystems.TALLY.equals(c.accountingSystem()));
    return data;
  }

  public Map<String, Object> patchConfig(
      MedmatePrincipal principal,
      String accountingSystem,
      Boolean autoSyncEnabled,
      String syncFrequency) {
    requireOwner(principal);
    UUID ph = principal.pharmacyId();
    requirePlan(ph);
    Instant now = Instant.now(clock);
    AccountingIntegration existing =
        integrations
            .findByPharmacyId(ph)
            .orElseGet(
                () ->
                    new AccountingIntegration(
                        UUID.randomUUID(),
                        ph,
                        accountingSystem == null ? AccountingSystems.TALLY : accountingSystem,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AccountingApiKeyStatuses.DISCONNECTED,
                        false,
                        null,
                        null,
                        null,
                        null,
                        now,
                        now));

    String system = accountingSystem == null ? existing.accountingSystem() : accountingSystem;
    if (!AccountingSystems.isValid(system)) {
      throw new AppException(
          "VALIDATION_ERROR", "accounting_system must be TALLY or ZOHO_BOOKS", 422);
    }
    boolean auto = autoSyncEnabled == null ? existing.autoSyncEnabled() : autoSyncEnabled;
    String freq = syncFrequency == null ? existing.syncFrequency() : syncFrequency;
    if (auto) {
      if (freq == null || !AccountingSyncFrequencies.isValid(freq)) {
        throw new AppException(
            "VALIDATION_ERROR", "sync_frequency required when auto_sync enabled", 422);
      }
    } else if (freq != null && !AccountingSyncFrequencies.isValid(freq)) {
      throw new AppException("VALIDATION_ERROR", "Invalid sync_frequency", 422);
    }

    Instant nextSync = null;
    if (auto) {
      nextSync = NextSyncAtCalculator.next(freq, clock);
    }

    String apiStatus = existing.apiKeyStatus();
    if (AccountingApiKeyStatuses.DISCONNECTED.equals(apiStatus)
        || existing.apiKeyStatus() == null) {
      // Connecting via PATCH sets system; Zoho tokens arrive via OAuth (stub: mark CONNECTED for
      // TALLY).
      if (AccountingSystems.TALLY.equals(system)) {
        apiStatus = AccountingApiKeyStatuses.CONNECTED;
      } else if (existing.zohoAccessToken() != null) {
        apiStatus = AccountingApiKeyStatuses.CONNECTED;
      }
    }

    AccountingIntegration updated =
        new AccountingIntegration(
            existing.id(),
            ph,
            system,
            existing.zohoOrganizationId(),
            existing.zohoOrganizationName(),
            existing.zohoAccessToken(),
            existing.zohoRefreshToken(),
            existing.zohoTokenExpiresAt(),
            apiStatus,
            auto,
            freq,
            nextSync,
            existing.lastSyncAt(),
            existing.lastSyncStatus(),
            existing.createdAt(),
            now);
    integrations.upsert(updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("accounting_system", system);
    data.put("auto_sync_enabled", auto);
    data.put("sync_frequency", freq);
    data.put("next_sync_at", nextSync == null ? null : nextSync.toString());
    data.put("updated_at", now.toString());
    return data;
  }

  /** Test/helper: attach Zoho OAuth tokens (encrypted at rest when cipher available). */
  public void connectZoho(
      UUID pharmacyId,
      String organizationId,
      String organizationName,
      String accessToken,
      String refreshToken,
      Instant expiresAt) {
    Instant now = Instant.now(clock);
    AccountingIntegration existing =
        integrations
            .findByPharmacyId(pharmacyId)
            .orElseGet(
                () ->
                    new AccountingIntegration(
                        UUID.randomUUID(),
                        pharmacyId,
                        AccountingSystems.ZOHO_BOOKS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AccountingApiKeyStatuses.DISCONNECTED,
                        false,
                        null,
                        null,
                        null,
                        null,
                        now,
                        now));
    AccountingIntegration updated =
        new AccountingIntegration(
            existing.id(),
            pharmacyId,
            AccountingSystems.ZOHO_BOOKS,
            organizationId,
            organizationName,
            encrypt(accessToken),
            encrypt(refreshToken),
            expiresAt,
            AccountingApiKeyStatuses.CONNECTED,
            existing.autoSyncEnabled(),
            existing.syncFrequency(),
            existing.nextSyncAt(),
            existing.lastSyncAt(),
            existing.lastSyncStatus(),
            existing.createdAt(),
            now);
    integrations.upsert(updated);
  }

  public Map<String, Object> exportTallyXml(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String syncType,
      LocalDate periodFrom,
      LocalDate periodTo) {
    requireOwner(principal);
    UUID ph = resolvePharmacy(principal, pharmacyId);
    requirePlan(ph);
    if (!AccountingSyncTypes.isTallyExportable(syncType)) {
      throw new AppException("VALIDATION_ERROR", "sync_type must be SALES, PURCHASES, or GST", 422);
    }
    if (periodFrom == null || periodTo == null || periodFrom.isAfter(periodTo)) {
      throw new AppException("INVALID_PERIOD", "period_from must be on or before period_to", 422);
    }
    List<AccountingVoucher> vouchers = loadVouchers(ph, syncType, periodFrom, periodTo);
    // SALES / PURCHASES / GST share the same Tally ENVELOPE/IMPORTDATA voucher XML shape.
    String xml = TallyXmlBuilder.buildSales(vouchers);
    byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
    String key =
        StorageObjectKeys.export(
            "tally_xml_" + ph + "_" + syncType.toLowerCase() + "_" + UUID.randomUUID() + ".xml");
    exports.put(key, bytes, "application/xml");
    String url = exports.createDownloadUrl(key, DOWNLOAD_TTL);
    Instant expires = Instant.now(clock).plus(DOWNLOAD_TTL);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("download_url", url);
    data.put("file_size_kb", Math.max(1, (bytes.length + 1023) / 1024));
    data.put("records_count", vouchers.size());
    data.put("expires_at", expires.toString());
    data.put("tally_import_instructions", TALLY_IMPORT_INSTRUCTIONS);
    return data;
  }

  public void processQueuedJobs() {
    for (AccountingSyncJob queued : jobs.findQueued(5)) {
      processJob(queued.id());
    }
  }

  public void processJob(UUID jobId) {
    AccountingSyncJob job = jobs.findById(jobId).orElse(null);
    if (job == null || !AccountingSyncStatuses.QUEUED.equals(job.status())) {
      return;
    }
    Instant started = Instant.now(clock);
    jobs.update(
        new AccountingSyncJob(
            job.id(),
            job.pharmacyId(),
            job.accountingSystem(),
            job.syncType(),
            job.periodFrom(),
            job.periodTo(),
            AccountingSyncStatuses.RUNNING,
            0,
            0,
            0,
            List.of(),
            job.triggeredBy(),
            job.queuedAt(),
            started,
            null));

    AccountingIntegration config = integrations.findByPharmacyId(job.pharmacyId()).orElse(null);
    List<Map<String, Object>> errors = new ArrayList<>();
    int synced = 0;
    int failed = 0;
    List<AccountingVoucher> vouchers =
        loadVouchers(job.pharmacyId(), job.syncType(), job.periodFrom(), job.periodTo());

    try {
      if (AccountingSystems.ZOHO_BOOKS.equals(job.accountingSystem())) {
        if (config == null) {
          throw new AppException(
              "ACCOUNTING_NOT_CONFIGURED", "No accounting system connected", 422);
        }
        config = ensureFreshZohoToken(config);
        for (AccountingVoucher v : vouchers) {
          ZohoBooksClientPort.SyncResult result = pushZoho(config, job.syncType(), v);
          if (result.errorCode() != null) {
            failed++;
            errors.add(
                Map.of(
                    "record_id",
                    v.platformId().toString(),
                    "record_type",
                    v.recordType(),
                    "error_code",
                    result.errorCode(),
                    "error_message",
                    result.errorMessage() == null ? "" : result.errorMessage()));
          } else {
            synced++;
          }
        }
      } else {
        // Tally: file-based; job marks records counted as synced (export is separate).
        synced = vouchers.size();
      }
      Instant done = Instant.now(clock);
      String status = AccountingSyncStatuses.COMPLETED;
      jobs.update(
          new AccountingSyncJob(
              job.id(),
              job.pharmacyId(),
              job.accountingSystem(),
              job.syncType(),
              job.periodFrom(),
              job.periodTo(),
              status,
              vouchers.size(),
              synced,
              failed,
              errors,
              job.triggeredBy(),
              job.queuedAt(),
              started,
              done));
      if (config != null) {
        integrations.upsert(
            new AccountingIntegration(
                config.id(),
                config.pharmacyId(),
                config.accountingSystem(),
                config.zohoOrganizationId(),
                config.zohoOrganizationName(),
                config.zohoAccessToken(),
                config.zohoRefreshToken(),
                config.zohoTokenExpiresAt(),
                config.apiKeyStatus(),
                config.autoSyncEnabled(),
                config.syncFrequency(),
                config.nextSyncAt(),
                done,
                status,
                config.createdAt(),
                done));
      }
    } catch (RuntimeException ex) {
      Instant done = Instant.now(clock);
      jobs.update(
          new AccountingSyncJob(
              job.id(),
              job.pharmacyId(),
              job.accountingSystem(),
              job.syncType(),
              job.periodFrom(),
              job.periodTo(),
              AccountingSyncStatuses.FAILED,
              vouchers.size(),
              synced,
              failed,
              errors,
              job.triggeredBy(),
              job.queuedAt(),
              started,
              done));
      if (!(ex instanceof AppException)) {
        throw ex;
      }
    }
  }

  public void runDueAutoSyncs() {
    Instant now = Instant.now(clock);
    for (AccountingIntegration cfg : integrations.findDueAutoSync(now, 20)) {
      if (jobs.hasActiveJob(cfg.pharmacyId())) {
        continue;
      }
      if (!planGate.allowsAccounting(cfg.pharmacyId())) {
        continue;
      }
      String frequency = cfg.syncFrequency();
      List<String> types = new ArrayList<>();
      types.add(AccountingSyncTypes.SALES);
      if (AccountingSyncFrequencies.WEEKLY.equals(frequency)) {
        types.add(AccountingSyncTypes.PURCHASES);
        types.add(AccountingSyncTypes.GST);
      }
      LocalDate to = LocalDate.ofInstant(now, NextSyncAtCalculator.IST);
      LocalDate from =
          AccountingSyncFrequencies.WEEKLY.equals(frequency) ? to.minusDays(7) : to.minusDays(1);
      for (String type : types) {
        AccountingSyncJob job =
            new AccountingSyncJob(
                UUID.randomUUID(),
                cfg.pharmacyId(),
                cfg.accountingSystem(),
                type,
                from,
                to,
                AccountingSyncStatuses.QUEUED,
                0,
                0,
                0,
                List.of(),
                AccountingTriggeredBy.SCHEDULER,
                now,
                null,
                null);
        jobs.insert(job);
      }
      Instant next = NextSyncAtCalculator.next(frequency, clock);
      integrations.upsert(
          new AccountingIntegration(
              cfg.id(),
              cfg.pharmacyId(),
              cfg.accountingSystem(),
              cfg.zohoOrganizationId(),
              cfg.zohoOrganizationName(),
              cfg.zohoAccessToken(),
              cfg.zohoRefreshToken(),
              cfg.zohoTokenExpiresAt(),
              cfg.apiKeyStatus(),
              cfg.autoSyncEnabled(),
              cfg.syncFrequency(),
              next,
              cfg.lastSyncAt(),
              cfg.lastSyncStatus(),
              cfg.createdAt(),
              now));
    }
  }

  AccountingIntegration ensureFreshZohoToken(AccountingIntegration config) {
    Instant expires = config.zohoTokenExpiresAt();
    Instant now = Instant.now(clock);
    if (expires != null && expires.isAfter(now.plus(TOKEN_REFRESH_SKEW))) {
      return config;
    }
    String refresh = decrypt(config.zohoRefreshToken());
    if (refresh == null || refresh.isBlank()) {
      throw new AppException("ZOHO_TOKEN_ERROR", "Zoho refresh token missing", 422);
    }
    ZohoBooksClientPort.TokenPair pair = zoho.refreshAccessToken(refresh);
    AccountingIntegration refreshed =
        new AccountingIntegration(
            config.id(),
            config.pharmacyId(),
            config.accountingSystem(),
            config.zohoOrganizationId(),
            config.zohoOrganizationName(),
            encrypt(pair.accessToken()),
            encrypt(pair.refreshToken() == null ? refresh : pair.refreshToken()),
            pair.expiresAt(),
            AccountingApiKeyStatuses.CONNECTED,
            config.autoSyncEnabled(),
            config.syncFrequency(),
            config.nextSyncAt(),
            config.lastSyncAt(),
            config.lastSyncStatus(),
            config.createdAt(),
            now);
    integrations.upsert(refreshed);
    return refreshed;
  }

  private ZohoBooksClientPort.SyncResult pushZoho(
      AccountingIntegration config, String syncType, AccountingVoucher voucher) {
    String access = decrypt(config.zohoAccessToken());
    String org = config.zohoOrganizationId();
    return switch (syncType) {
      case AccountingSyncTypes.SALES -> zoho.upsertSalesVoucher(access, org, voucher);
      case AccountingSyncTypes.PURCHASES -> zoho.upsertPurchaseVoucher(access, org, voucher);
      case AccountingSyncTypes.GST -> zoho.upsertGstEntry(access, org, voucher);
      case AccountingSyncTypes.EXPENSES -> zoho.upsertExpense(access, org, voucher);
      default -> ZohoBooksClientPort.SyncResult.fail("INVALID_SYNC_TYPE", "Unknown sync type");
    };
  }

  private List<AccountingVoucher> loadVouchers(
      UUID pharmacyId, String syncType, LocalDate from, LocalDate to) {
    return switch (syncType) {
      case AccountingSyncTypes.SALES -> data.sales(pharmacyId, from, to);
      case AccountingSyncTypes.PURCHASES -> data.purchases(pharmacyId, from, to);
      case AccountingSyncTypes.EXPENSES -> data.expenses(pharmacyId, from, to);
      case AccountingSyncTypes.GST -> data.gstEntries(pharmacyId, from, to);
      // Corrupt/legacy job rows: still attempt push so INVALID_SYNC_TYPE is recorded per voucher.
      default -> data.sales(pharmacyId, from, to);
    };
  }

  private String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    if (cipher == null) {
      // ponytail: plaintext when cipher bean absent; staging/prod inject bankAccountCipher (SM).
      return plaintext;
    }
    return cipher.encrypt(plaintext);
  }

  private String decrypt(String ciphertext) {
    if (ciphertext == null) {
      return null;
    }
    if (cipher == null) {
      return ciphertext;
    }
    return cipher.decrypt(ciphertext);
  }

  private void requirePlan(UUID pharmacyId) {
    if (!planGate.allowsAccounting(pharmacyId)) {
      throw new AppException(
          "PLAN_UPGRADE_REQUIRED", "Accounting integration requires Growth plan or higher", 403);
    }
  }

  private static void requireOwner(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "pharmacy_owner role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }

  private static void requireOwnerOrOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.ADMIN_OPERATIONS
        && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "pharmacy_owner or admin_operations required", 403);
    }
  }

  private static UUID resolvePharmacy(MedmatePrincipal principal, UUID pharmacyId) {
    if (pharmacyId == null) {
      return principal.pharmacyId();
    }
    if (!pharmacyId.equals(principal.pharmacyId())) {
      throw new AppException("FORBIDDEN", "pharmacy_id does not match authenticated pharmacy", 403);
    }
    return pharmacyId;
  }
}
