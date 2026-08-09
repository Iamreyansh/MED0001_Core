package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.LiveZohoBooksClient;
import com.nammamedmate.integration.adapter.out.client.StubZohoBooksClient;
import com.nammamedmate.integration.adapter.out.persistence.JdbcAccountingSyncJobStore;
import com.nammamedmate.integration.adapter.out.persistence.LocalAccountingExportObjectStore;
import com.nammamedmate.integration.application.port.out.AccountingDataPort;
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
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountingBranchCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID PH = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

  @TempDir Path temp;

  @Test
  void remainingBranches() {
    InMemoryStores.AccountingIntegrations integrations =
        new InMemoryStores.AccountingIntegrations();
    InMemoryStores.AccountingJobs jobs = new InMemoryStores.AccountingJobs();
    StubZohoBooksClient zoho = new StubZohoBooksClient(Clock.fixed(NOW, ZoneOffset.UTC));
    AccountingService service =
        new AccountingService(
            integrations,
            jobs,
            vouchers(),
            zoho,
            new LocalAccountingExportObjectStore(temp, "file://" + temp),
            id -> true,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    MedmatePrincipal owner =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH, TokenScope.FULL, "o");

    // period null branches (INVALID_PERIOD)
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    null,
                    LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");

    // auto=true + freq null (existing has no frequency yet)
    assertThatThrownBy(() -> service.patchConfig(owner, AccountingSystems.TALLY, true, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    // patch with null system on empty store → default TALLY; null auto keeps false
    service.patchConfig(owner, null, null, null);
    // auto=true + valid frequency (inner if false branch)
    service.patchConfig(owner, AccountingSystems.TALLY, true, AccountingSyncFrequencies.DAILY);
    // auto=true + invalid frequency
    assertThatThrownBy(() -> service.patchConfig(owner, AccountingSystems.TALLY, true, "HOURLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    // CONNECTED with null lastSyncAt → then after sync non-null
    Map<String, Object> before = service.getConfig(owner);
    assertThat(before.get("last_sync_at")).isNull();
    service.triggerSync(
        owner,
        PH,
        AccountingSystems.TALLY,
        AccountingSyncTypes.SALES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 2));
    assertThat(service.getConfig(owner).get("last_sync_at")).isNotNull();

    // apiKeyStatus null branch + ZOHO without token stays disconnected
    Instant t = NOW;
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            PH,
            AccountingSystems.ZOHO_BOOKS,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            AccountingSyncFrequencies.DAILY,
            null,
            null,
            null,
            t,
            t));
    service.patchConfig(owner, AccountingSystems.ZOHO_BOOKS, null, null);
    assertThat(integrations.findByPharmacyId(PH).orElseThrow().apiKeyStatus()).isNull();

    // export null period edges
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            PH,
            AccountingSystems.TALLY,
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.CONNECTED,
            false,
            null,
            null,
            null,
            null,
            t,
            t));
    assertThatThrownBy(
            () ->
                service.exportTallyXml(
                    owner, PH, AccountingSyncTypes.SALES, null, LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(
            () ->
                service.exportTallyXml(
                    owner, PH, AccountingSyncTypes.GST, LocalDate.of(2026, 7, 1), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThat(AccountingSyncTypes.isTallyExportable(AccountingSyncTypes.GST)).isTrue();
    assertThat(AccountingSyncTypes.isTallyExportable(AccountingSyncTypes.PURCHASES)).isTrue();

    // processJob early return when not QUEUED
    UUID done = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            done,
            PH,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            AccountingSyncStatuses.COMPLETED,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            NOW,
            NOW,
            NOW));
    service.processJob(done);

    // Tally success with config == null (integration row missing)
    UUID orphan = UUID.randomUUID();
    UUID otherPh = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            orphan,
            otherPh,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            AccountingSyncStatuses.QUEUED,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            NOW,
            null,
            null));
    service.processJob(orphan);
    assertThat(jobs.findById(orphan).orElseThrow().status())
        .isEqualTo(AccountingSyncStatuses.COMPLETED);

    // null errorMessage on failed sync result
    ZohoBooksClientPort nullMsgZoho =
        new ZohoBooksClientPort() {
          @Override
          public TokenPair refreshAccessToken(String refreshToken) {
            return new TokenPair("a", "r", NOW.plusSeconds(3600));
          }

          @Override
          public SyncResult upsertSalesVoucher(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.fail("E", null);
          }

          @Override
          public SyncResult upsertPurchaseVoucher(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.ok("x", true);
          }

          @Override
          public SyncResult upsertGstEntry(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.ok("x", true);
          }

          @Override
          public SyncResult upsertExpense(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.ok("x", true);
          }
        };
    AccountingService s2 =
        new AccountingService(
            integrations,
            jobs,
            vouchers(),
            nullMsgZoho,
            new LocalAccountingExportObjectStore(temp, "file://" + temp),
            id -> true,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    s2.connectZoho(PH, "o", "n", "a", "r", NOW.plusSeconds(7200));
    Map<String, Object> q =
        s2.triggerSync(
            owner,
            PH,
            AccountingSystems.ZOHO_BOOKS,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors =
        (List<Map<String, Object>>)
            s2.syncStatus(owner, UUID.fromString(q.get("job_id").toString())).get("errors");
    assertThat(errors.get(0).get("error_message")).isEqualTo("");

    // auto-sync: active job skip + plan deny skip
    UUID duePh = UUID.randomUUID();
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            duePh,
            AccountingSystems.TALLY,
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.CONNECTED,
            true,
            AccountingSyncFrequencies.DAILY,
            NOW.minusSeconds(1),
            null,
            null,
            NOW,
            NOW));
    jobs.insert(
        new AccountingSyncJob(
            UUID.randomUUID(),
            duePh,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            AccountingSyncStatuses.RUNNING,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.SCHEDULER,
            NOW,
            NOW,
            null));
    service.runDueAutoSyncs();

    UUID deniedPh = UUID.randomUUID();
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            deniedPh,
            AccountingSystems.TALLY,
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.CONNECTED,
            true,
            AccountingSyncFrequencies.DAILY,
            NOW.minusSeconds(1),
            null,
            null,
            NOW,
            NOW));
    AccountingService denied =
        new AccountingService(
            integrations,
            jobs,
            vouchers(),
            zoho,
            new LocalAccountingExportObjectStore(temp, "file://" + temp),
            id -> !id.equals(deniedPh),
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    denied.runDueAutoSyncs();

    // token refresh: expires null; blank refresh
    AccountingIntegration base = integrations.findByPharmacyId(PH).orElseThrow();
    integrations.upsert(
        new AccountingIntegration(
            base.id(),
            PH,
            AccountingSystems.ZOHO_BOOKS,
            "o",
            "n",
            "access",
            "refresh",
            null,
            AccountingApiKeyStatuses.CONNECTED,
            false,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    s2.ensureFreshZohoToken(integrations.findByPharmacyId(PH).orElseThrow());

    integrations.upsert(
        new AccountingIntegration(
            base.id(),
            PH,
            AccountingSystems.ZOHO_BOOKS,
            "o",
            "n",
            "access",
            "   ",
            NOW.minusSeconds(1),
            AccountingApiKeyStatuses.CONNECTED,
            false,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThatThrownBy(
            () -> s2.ensureFreshZohoToken(integrations.findByPharmacyId(PH).orElseThrow()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ZOHO_TOKEN_ERROR");

    // encrypt(null) via connectZoho
    service.connectZoho(PH, "o", "n", null, null, NOW.plusSeconds(10));

    // requireOwnerOrOps(null)
    assertThatThrownBy(() -> service.syncStatus(null, done))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    // triggerSync periodTo null; auto-sync with invalid frequency
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            PH,
            AccountingSystems.TALLY,
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.CONNECTED,
            false,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThatThrownBy(() -> service.patchConfig(owner, AccountingSystems.TALLY, true, "HOURLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // JDBC toJson(null errors) + ts non-null
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    JdbcAccountingSyncJobStore store = new JdbcAccountingSyncJobStore(jdbc, new ObjectMapper());
    store.insert(
        new AccountingSyncJob(
            UUID.randomUUID(),
            PH,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            AccountingSyncStatuses.COMPLETED,
            0,
            0,
            0,
            null,
            AccountingTriggeredBy.MANUAL,
            NOW,
            NOW,
            NOW));

    // LiveZoho access_token missing (null) + blank firstNonBlank entry
    LiveZohoBooksClient live =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://accounts.zoho.in",
            "https://books",
            new ObjectMapper(),
            req -> {
              if (req.uri().getPath().contains("/token")) {
                return "{}";
              }
              return "{\"invoice\":{\"invoice_id\":\"\"},\"bill\":{\"bill_id\":\"\"},\"journal\":{\"journal_id\":\"\"},\"expense\":{\"expense_id\":\"\"},\"invoice_id\":\"\"}";
            });
    assertThatThrownBy(() -> live.refreshAccessToken("r")).isInstanceOf(AppException.class);
    assertThat(
            live.upsertSalesVoucher(
                    "a",
                    "o",
                    new AccountingVoucher(
                        UUID.randomUUID(),
                        "SALES_INVOICE",
                        "1",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        1,
                        0,
                        1))
                .voucherId())
        .isEqualTo("zoho-ok");
  }

  private static AccountingDataPort vouchers() {
    return new AccountingDataPort() {
      @Override
      public List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of(
            new AccountingVoucher(
                UUID.randomUUID(),
                "SALES_INVOICE",
                "1",
                LocalDate.of(2026, 7, 1),
                "A",
                "29ABCDE1234F1Z5",
                100,
                0,
                100));
      }

      @Override
      public List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }
    };
  }
}
