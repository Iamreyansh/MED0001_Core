package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.LiveZohoBooksClient;
import com.nammamedmate.integration.adapter.out.client.StubZohoBooksClient;
import com.nammamedmate.integration.adapter.out.persistence.JdbcAccountingIntegrationStore;
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
import com.nammamedmate.integration.domain.NextSyncAtCalculator;
import com.nammamedmate.integration.domain.TallyXmlBuilder;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountingRemainingCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-20T22:00:00Z"); // Tue 03:30 IST
  private static final UUID PH = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @TempDir Path temp;

  @Test
  void validationAndConfigBranches() {
    InMemoryStores.AccountingIntegrations integrations =
        new InMemoryStores.AccountingIntegrations();
    InMemoryStores.AccountingJobs jobs = new InMemoryStores.AccountingJobs();
    AccountingService service =
        service(integrations, jobs, data(), new StubZohoBooksClient(clock()), true);
    MedmatePrincipal owner = owner();

    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    "BAD",
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.TALLY,
                    "NOPE",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
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

    service.patchConfig(owner, AccountingSystems.TALLY, false, null);
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.ZOHO_BOOKS,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNTING_NOT_CONFIGURED");

    assertThatThrownBy(() -> service.patchConfig(owner, "BAD", false, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchConfig(owner, AccountingSystems.TALLY, true, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchConfig(owner, AccountingSystems.TALLY, false, "BAD"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // Existing disconnected row + Zoho tokens → CONNECTED on patch.
    Instant t = Instant.now(clock());
    integrations.upsert(
        new AccountingIntegration(
            UUID.randomUUID(),
            PH,
            AccountingSystems.ZOHO_BOOKS,
            "o",
            "n",
            "tok",
            "ref",
            t.plusSeconds(3600),
            AccountingApiKeyStatuses.DISCONNECTED,
            false,
            null,
            null,
            null,
            null,
            t,
            t));
    service.patchConfig(owner, null, false, null);
    assertThat(integrations.findByPharmacyId(PH).orElseThrow().apiKeyStatus())
        .isEqualTo(AccountingApiKeyStatuses.CONNECTED);

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
            AccountingApiKeyStatuses.DISCONNECTED,
            false,
            null,
            null,
            null,
            null,
            t,
            t));
    Map<String, Object> cfg = service.getConfig(owner);
    assertThat(cfg.get("connected_system")).isNull();
  }

  @Test
  void exportAndStatusAndAuthEdges() {
    InMemoryStores.AccountingIntegrations integrations =
        new InMemoryStores.AccountingIntegrations();
    InMemoryStores.AccountingJobs jobs = new InMemoryStores.AccountingJobs();
    AccountingService service =
        service(integrations, jobs, data(), new StubZohoBooksClient(clock()), true);
    MedmatePrincipal owner = owner();
    service.patchConfig(owner, AccountingSystems.TALLY, false, null);

    assertThatThrownBy(
            () ->
                service.exportTallyXml(
                    owner,
                    PH,
                    AccountingSyncTypes.EXPENSES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.exportTallyXml(
                    owner,
                    PH,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 2),
                    LocalDate.of(2026, 7, 1)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    service.exportTallyXml(
        owner,
        null,
        AccountingSyncTypes.PURCHASES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 2));

    UUID jobId = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            jobId,
            PH,
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
    Map<String, Object> status = service.syncStatus(owner, jobId);
    assertThat(status.get("started_at")).isNull();
    assertThat(status.get("completed_at")).isNull();

    assertThatThrownBy(() -> service.syncStatus(owner, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    MedmatePrincipal noPh =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "x");
    assertThatThrownBy(() -> service.getConfig(noPh))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal ownerNoPhOnJob =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "y");
    // requireOwnerOrOps allows owner without pharmacy until pharmacy check
    assertThatThrownBy(() -> service.syncStatus(ownerNoPhOnJob, jobId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "s");
    assertThat(service.syncStatus(superAdmin, jobId).get("job_id")).isEqualTo(jobId.toString());

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c");
    assertThatThrownBy(() -> service.syncStatus(customer, jobId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void zohoRefreshMissingAndRuntimeFailureAndAutoSyncSkips() {
    InMemoryStores.AccountingIntegrations integrations =
        new InMemoryStores.AccountingIntegrations();
    InMemoryStores.AccountingJobs jobs = new InMemoryStores.AccountingJobs();
    AtomicBoolean boom = new AtomicBoolean(false);
    ZohoBooksClientPort zoho =
        new ZohoBooksClientPort() {
          @Override
          public TokenPair refreshAccessToken(String refreshToken) {
            return new TokenPair("a", null, NOW.plusSeconds(3600));
          }

          @Override
          public SyncResult upsertSalesVoucher(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            if (boom.get()) {
              throw new IllegalStateException("boom");
            }
            return SyncResult.ok("v", true);
          }

          @Override
          public SyncResult upsertPurchaseVoucher(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.ok("v", true);
          }

          @Override
          public SyncResult upsertGstEntry(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.ok("v", true);
          }

          @Override
          public SyncResult upsertExpense(
              String accessToken, String organizationId, AccountingVoucher voucher) {
            return SyncResult.fail("INVALID_SYNC_TYPE", "x");
          }
        };
    AccountingService service = service(integrations, jobs, data(), zoho, true);
    MedmatePrincipal owner = owner();
    service.connectZoho(PH, "org", "n", "access", "refresh", NOW.minusSeconds(10));
    // refresh with null refresh token in response — uses old refresh
    service.triggerSync(
        owner,
        PH,
        AccountingSystems.ZOHO_BOOKS,
        AccountingSyncTypes.SALES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 2));

    // missing refresh token
    AccountingIntegration cfg = integrations.findByPharmacyId(PH).orElseThrow();
    integrations.upsert(
        new AccountingIntegration(
            cfg.id(),
            cfg.pharmacyId(),
            cfg.accountingSystem(),
            cfg.zohoOrganizationId(),
            cfg.zohoOrganizationName(),
            cfg.zohoAccessToken(),
            null,
            NOW.minusSeconds(1),
            cfg.apiKeyStatus(),
            cfg.autoSyncEnabled(),
            cfg.syncFrequency(),
            cfg.nextSyncAt(),
            cfg.lastSyncAt(),
            cfg.lastSyncStatus(),
            cfg.createdAt(),
            cfg.updatedAt()));
    UUID jobId = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            jobId,
            PH,
            AccountingSystems.ZOHO_BOOKS,
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
    service.processJob(jobId);
    assertThat(jobs.findById(jobId).orElseThrow().status())
        .isEqualTo(AccountingSyncStatuses.FAILED);

    // runtime exception rethrown
    service.connectZoho(PH, "org", "n", "access", "refresh", NOW.plusSeconds(7200));
    boom.set(true);
    UUID job2 = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            job2,
            PH,
            AccountingSystems.ZOHO_BOOKS,
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
    assertThatThrownBy(() -> service.processJob(job2)).isInstanceOf(IllegalStateException.class);

    // auto-sync skips: active job + plan deny
    AccountingService denied = service(integrations, jobs, data(), zoho, false);
    AccountingIntegration due =
        new AccountingIntegration(
            UUID.randomUUID(),
            UUID.randomUUID(),
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
            NOW);
    integrations.upsert(due);
    jobs.insert(
        new AccountingSyncJob(
            UUID.randomUUID(),
            due.pharmacyId(),
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
    denied.runDueAutoSyncs();
    service.runDueAutoSyncs(); // plan denied pharmacy skipped in denied; service allows but active

    // bad sync type on queued job hits default switch
    UUID badType = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            badType,
            PH,
            AccountingSystems.ZOHO_BOOKS,
            "UNKNOWN",
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
    service.connectZoho(PH, "org", "n", "access", "refresh", NOW.plusSeconds(7200));
    boom.set(false);
    service.processJob(badType);

    new AccountingSyncJobProcessor(service).pollQueuedJobs();
    new AccountingAutoSyncScheduler(service).tick();
  }

  @Test
  void domainAndClientAndJdbcEdges() throws Exception {
    assertThat(AccountingSyncTypes.isValid(null)).isFalse();
    assertThat(AccountingSyncTypes.isTallyExportable(null)).isFalse();
    assertThat(
            TallyXmlBuilder.buildSales(
                List.of(
                    new AccountingVoucher(
                        UUID.randomUUID(),
                        "SALES_INVOICE",
                        "1",
                        LocalDate.of(2026, 7, 1),
                        null,
                        null,
                        100,
                        0,
                        100))))
        .contains("<PARTYLEDGERNAME></PARTYLEDGERNAME>");
    Instant weekly =
        NextSyncAtCalculator.next(
            AccountingSyncFrequencies.WEEKLY,
            Clock.fixed(
                Instant.parse("2026-07-20T01:00:00Z"), ZoneOffset.UTC)); // Mon 06:30 IST past 02:00
    assertThat(weekly).isAfter(Instant.parse("2026-07-20T01:00:00Z"));

    StubZohoBooksClient stub = new StubZohoBooksClient(clock());
    AccountingVoucher nullGst =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "1",
            LocalDate.of(2026, 7, 1),
            "A",
            null,
            100,
            0,
            100);
    assertThat(stub.upsertSalesVoucher("t", "o", nullGst).created()).isTrue();

    LiveZohoBooksClient live =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://accounts.zoho.in/",
            "https://books/",
            new ObjectMapper(),
            req -> {
              if (req.uri().getPath().contains("/token")) {
                return "{\"access_token\":\"\",\"expires_in\":1}";
              }
              if (req.method().equals("GET")) {
                return "{}";
              }
              String path = req.uri().getPath();
              if (path.contains("dup")) {
                return "{\"code\":\"3041\",\"invoice\":{\"invoice_id\":\"dup1\"}}";
              }
              if (path.contains("err")) {
                return "{\"error_code\":\"E1\",\"message\":\"bad\"}";
              }
              if (path.contains("msgdup")) {
                return "{\"message\":\"duplicate\",\"invoice\":{\"invoice_id\":\"d2\"}}";
              }
              if (path.contains("codehi")) {
                return "{\"code\":4001,\"message\":\"x\"}";
              }
              if (path.contains("emptyid")) {
                return "{}";
              }
              if (path.contains("app")) {
                throw new AppException("ZOHO_UNAVAILABLE", "x", 503);
              }
              return "{\"invoice_id\":\"root\"}";
            });
    assertThatThrownBy(() -> live.refreshAccessToken("r")).isInstanceOf(AppException.class);

    AccountingVoucher v =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "1",
            LocalDate.of(2026, 7, 1),
            "A",
            "g",
            100,
            0,
            100);
    // recreate with path-driven responses via organizationId hack — use different clients
    LiveZohoBooksClient live2 =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://accounts.zoho.in",
            "https://www.zohoapis.in/books/v3",
            new ObjectMapper(),
            req -> {
              String body = req.body() == null ? "" : req.body();
              if (req.uri().getPath().contains("/token")) {
                return "{\"access_token\":\"at\",\"expires_in\":10}";
              }
              if (body.contains("dup-ref")) {
                return "{\"code\":\"3041\",\"invoice\":{\"invoice_id\":\"dup1\"}}";
              }
              if (body.contains("msgdup")) {
                return "{\"message\":\"duplicate\",\"invoice\":{\"invoice_id\":\"d2\"}}";
              }
              if (body.contains("err-ref")) {
                return "{\"error_code\":\"E1\",\"message\":\"bad\"}";
              }
              if (body.contains("codehi")) {
                return "{\"code\":4001,\"message\":\"x\"}";
              }
              if (body.contains("emptyid")) {
                return "{}";
              }
              if (body.contains("appex")) {
                throw new AppException("ZOHO_UNAVAILABLE", "x", 503);
              }
              return "{\"invoice_id\":\"root\"}";
            });
    assertThat(live2.refreshAccessToken("r").accessToken()).isEqualTo("at");
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111101"),
                        "SALES_INVOICE",
                        "dup-ref",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .created())
        .isFalse();
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111102"),
                        "SALES_INVOICE",
                        "msgdup",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .created())
        .isFalse();
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111103"),
                        "SALES_INVOICE",
                        "err-ref",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .errorCode())
        .isEqualTo("E1");
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111104"),
                        "SALES_INVOICE",
                        "codehi",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .errorCode())
        .isEqualTo("ZOHO_ERROR");
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111105"),
                        "SALES_INVOICE",
                        "emptyid",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .voucherId())
        .isEqualTo("zoho-ok");
    assertThat(
            live2
                .upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111106"),
                        "SALES_INVOICE",
                        "rootid",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100))
                .voucherId())
        .isEqualTo("root");
    assertThatThrownBy(
            () ->
                live2.upsertSalesVoucher(
                    "at",
                    "org",
                    new AccountingVoucher(
                        UUID.fromString("11111111-1111-4111-8111-111111111107"),
                        "SALES_INVOICE",
                        "appex",
                        LocalDate.of(2026, 7, 1),
                        "A",
                        "g",
                        100,
                        0,
                        100)))
        .isInstanceOf(AppException.class);

    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    JdbcAccountingSyncJobStore jobStore = new JdbcAccountingSyncJobStore(jdbc, new ObjectMapper());
    Mockito.when(
            jdbc.queryForObject(
                Mockito.anyString(),
                Mockito.eq(Integer.class),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(null);
    assertThat(jobStore.hasActiveJob(PH)).isFalse();

    // force toJson/readErrors error paths via broken mapper
    ObjectMapper broken =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("ser");
          }

          @Override
          public <T> T readValue(
              String content, com.fasterxml.jackson.core.type.TypeReference<T> valueTypeRef) {
            throw new RuntimeException("de");
          }
        };
    JdbcAccountingSyncJobStore badStore = new JdbcAccountingSyncJobStore(jdbc, broken);
    assertThatThrownBy(
            () ->
                badStore.insert(
                    new AccountingSyncJob(
                        UUID.randomUUID(),
                        PH,
                        AccountingSystems.TALLY,
                        AccountingSyncTypes.SALES,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        AccountingSyncStatuses.QUEUED,
                        0,
                        0,
                        0,
                        null,
                        AccountingTriggeredBy.MANUAL,
                        NOW,
                        NOW,
                        NOW)))
        .isInstanceOf(IllegalStateException.class);

    JdbcAccountingIntegrationStore intStore = new JdbcAccountingIntegrationStore(jdbc);
    // mapRow null timestamps via query callback
    Mockito.when(
            jdbc.query(
                Mockito.anyString(),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq(PH)))
        .thenAnswer(
            inv -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = Mockito.mock(java.sql.ResultSet.class);
              Mockito.when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              Mockito.when(rs.getObject("pharmacy_id")).thenReturn(PH);
              Mockito.when(rs.getString("accounting_system")).thenReturn(AccountingSystems.TALLY);
              Mockito.when(rs.getString("zoho_organization_id")).thenReturn(null);
              Mockito.when(rs.getString("zoho_organization_name")).thenReturn(null);
              Mockito.when(rs.getString("zoho_access_token")).thenReturn(null);
              Mockito.when(rs.getString("zoho_refresh_token")).thenReturn(null);
              Mockito.when(rs.getTimestamp("zoho_token_expires_at")).thenReturn(null);
              Mockito.when(rs.getString("api_key_status"))
                  .thenReturn(AccountingApiKeyStatuses.DISCONNECTED);
              Mockito.when(rs.getBoolean("auto_sync_enabled")).thenReturn(false);
              Mockito.when(rs.getString("sync_frequency")).thenReturn(null);
              Mockito.when(rs.getTimestamp("next_sync_at")).thenReturn(null);
              Mockito.when(rs.getTimestamp("last_sync_at")).thenReturn(null);
              Mockito.when(rs.getString("last_sync_status")).thenReturn(null);
              Mockito.when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              Mockito.when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(intStore.findByPharmacyId(PH)).isPresent();

    // started/completed non-null in job map
    ObjectMapper om = new ObjectMapper();
    JdbcAccountingSyncJobStore okStore = new JdbcAccountingSyncJobStore(jdbc, om);
    UUID jid = UUID.randomUUID();
    Mockito.when(
            jdbc.query(
                Mockito.anyString(),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq(jid)))
        .thenAnswer(
            inv -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = Mockito.mock(java.sql.ResultSet.class);
              Mockito.when(rs.getObject("id")).thenReturn(jid);
              Mockito.when(rs.getObject("pharmacy_id")).thenReturn(PH);
              Mockito.when(rs.getString("accounting_system")).thenReturn(AccountingSystems.TALLY);
              Mockito.when(rs.getString("sync_type")).thenReturn(AccountingSyncTypes.SALES);
              Mockito.when(rs.getDate("period_from"))
                  .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 7, 1)));
              Mockito.when(rs.getDate("period_to"))
                  .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 7, 2)));
              Mockito.when(rs.getString("status")).thenReturn(AccountingSyncStatuses.COMPLETED);
              Mockito.when(rs.getInt("records_processed")).thenReturn(0);
              Mockito.when(rs.getInt("records_synced")).thenReturn(0);
              Mockito.when(rs.getInt("records_failed")).thenReturn(0);
              Mockito.when(rs.getString("errors")).thenReturn(null);
              Mockito.when(rs.getString("triggered_by")).thenReturn(AccountingTriggeredBy.MANUAL);
              Mockito.when(rs.getTimestamp("queued_at")).thenReturn(Timestamp.from(NOW));
              Mockito.when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(NOW));
              Mockito.when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(okStore.findById(jid).orElseThrow().startedAt()).isEqualTo(NOW);

    Mockito.when(
            jdbc.query(
                Mockito.anyString(),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq(jid)))
        .thenAnswer(
            inv -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = Mockito.mock(java.sql.ResultSet.class);
              Mockito.when(rs.getObject("id")).thenReturn(jid);
              Mockito.when(rs.getObject("pharmacy_id")).thenReturn(PH);
              Mockito.when(rs.getString("accounting_system")).thenReturn(AccountingSystems.TALLY);
              Mockito.when(rs.getString("sync_type")).thenReturn(AccountingSyncTypes.SALES);
              Mockito.when(rs.getDate("period_from"))
                  .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 7, 1)));
              Mockito.when(rs.getDate("period_to"))
                  .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 7, 2)));
              Mockito.when(rs.getString("status")).thenReturn(AccountingSyncStatuses.COMPLETED);
              Mockito.when(rs.getInt("records_processed")).thenReturn(0);
              Mockito.when(rs.getInt("records_synced")).thenReturn(0);
              Mockito.when(rs.getInt("records_failed")).thenReturn(0);
              Mockito.when(rs.getString("errors")).thenReturn("not-json");
              Mockito.when(rs.getString("triggered_by")).thenReturn(AccountingTriggeredBy.MANUAL);
              Mockito.when(rs.getTimestamp("queued_at")).thenReturn(Timestamp.from(NOW));
              Mockito.when(rs.getTimestamp("started_at")).thenReturn(null);
              Mockito.when(rs.getTimestamp("completed_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> okStore.findById(jid)).isInstanceOf(IllegalStateException.class);

    // cipher encrypt/decrypt paths
    AccountingService withCipher =
        service(
            new InMemoryStores.AccountingIntegrations(),
            new InMemoryStores.AccountingJobs(),
            data(),
            new StubZohoBooksClient(clock()),
            true,
            new AesGcmCipher(new byte[32]));
    withCipher.connectZoho(PH, "o", "n", "plain-access", "plain-refresh", NOW.plusSeconds(100));
  }

  private AccountingService service(
      InMemoryStores.AccountingIntegrations integrations,
      InMemoryStores.AccountingJobs jobs,
      AccountingDataPort data,
      ZohoBooksClientPort zoho,
      boolean plan) {
    return service(integrations, jobs, data, zoho, plan, null);
  }

  private AccountingService service(
      InMemoryStores.AccountingIntegrations integrations,
      InMemoryStores.AccountingJobs jobs,
      AccountingDataPort data,
      ZohoBooksClientPort zoho,
      boolean plan,
      AesGcmCipher cipher) {
    return new AccountingService(
        integrations,
        jobs,
        data,
        zoho,
        new LocalAccountingExportObjectStore(temp, "file://" + temp),
        id -> plan,
        cipher,
        clock());
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static MedmatePrincipal owner() {
    return new MedmatePrincipal(
        UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH, TokenScope.FULL, "o");
  }

  private static AccountingDataPort data() {
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
