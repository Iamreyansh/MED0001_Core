package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.adapter.out.client.StubZohoBooksClient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountingCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z"); // Monday
  private static final UUID PH = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @TempDir Path temp;

  private InMemoryStores.AccountingIntegrations integrations;
  private InMemoryStores.AccountingJobs jobs;
  private AccountingService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal ops;

  @BeforeEach
  void setUp() {
    integrations = new InMemoryStores.AccountingIntegrations();
    jobs = new InMemoryStores.AccountingJobs();
    AccountingDataPort data =
        new AccountingDataPort() {
          @Override
          public List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to) {
            return List.of(sample("SALES_INVOICE"));
          }

          @Override
          public List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to) {
            return List.of(sample("PURCHASE_INVOICE"));
          }

          @Override
          public List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to) {
            return List.of(sample("EXPENSE"));
          }

          @Override
          public List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to) {
            return List.of(sample("GST_ENTRY"));
          }
        };
    service =
        new AccountingService(
            integrations,
            jobs,
            data,
            new StubZohoBooksClient(Clock.fixed(NOW, ZoneOffset.UTC)),
            new LocalAccountingExportObjectStore(temp, "file://" + temp),
            id -> true,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    owner =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH, TokenScope.FULL, "o");
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "a");
  }

  @Test
  void domainHelpersAndTallyXml() {
    assertThat(AccountingSystems.isValid("TALLY")).isTrue();
    assertThat(AccountingSystems.isValid("X")).isFalse();
    assertThat(AccountingSyncTypes.isValid("EXPENSES")).isTrue();
    assertThat(AccountingSyncTypes.isTallyExportable("EXPENSES")).isFalse();
    assertThat(AccountingSyncFrequencies.isValid("WEEKLY")).isTrue();
    assertThat(AccountingSyncStatuses.isActive("QUEUED")).isTrue();
    String xml = TallyXmlBuilder.buildSales(List.of(sample("SALES_INVOICE")));
    assertThat(xml).contains("<ENVELOPE>").contains("&amp;");
    Instant weekly = NextSyncAtCalculator.next("WEEKLY", Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(weekly.atZone(NextSyncAtCalculator.IST).getDayOfWeek().getValue()).isEqualTo(1);
  }

  @Test
  void zohoSyncTypesAndAdminStatusAndLocalStoreDefaults() {
    service.connectZoho(PH, "org", "Name", "a", "r", NOW.plusSeconds(7200));
    for (String type :
        List.of(
            AccountingSyncTypes.SALES,
            AccountingSyncTypes.PURCHASES,
            AccountingSyncTypes.EXPENSES,
            AccountingSyncTypes.GST)) {
      Map<String, Object> q =
          service.triggerSync(
              owner,
              PH,
              AccountingSystems.ZOHO_BOOKS,
              type,
              LocalDate.of(2026, 7, 1),
              LocalDate.of(2026, 7, 31));
      Map<String, Object> status =
          service.syncStatus(ops, UUID.fromString(q.get("job_id").toString()));
      assertThat(status.get("status")).isEqualTo(AccountingSyncStatuses.COMPLETED);
    }
    assertThat(new LocalAccountingExportObjectStore()).isNotNull();
    AccountingAutoSyncScheduler scheduler = new AccountingAutoSyncScheduler(service);
    scheduler.tick();
  }

  @Test
  void configConnectedAndWeeklyAutoSyncAndForbiddenPaths() {
    Map<String, Object> patched =
        service.patchConfig(owner, AccountingSystems.TALLY, true, AccountingSyncFrequencies.WEEKLY);
    assertThat(patched.get("sync_frequency")).isEqualTo("WEEKLY");
    Map<String, Object> cfg = service.getConfig(owner);
    assertThat(cfg.get("connected_system")).isEqualTo(AccountingSystems.TALLY);
    assertThat(cfg.get("tally_xml_available")).isEqualTo(true);

    AccountingIntegration row = integrations.findByPharmacyId(PH).orElseThrow();
    integrations.upsert(
        new AccountingIntegration(
            row.id(),
            row.pharmacyId(),
            row.accountingSystem(),
            null,
            null,
            null,
            null,
            null,
            AccountingApiKeyStatuses.CONNECTED,
            true,
            AccountingSyncFrequencies.WEEKLY,
            NOW.minusSeconds(1),
            null,
            null,
            row.createdAt(),
            row.updatedAt()));
    service.runDueAutoSyncs();

    MedmatePrincipal staff =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PH, TokenScope.FULL, "s");
    assertThatThrownBy(() -> service.getConfig(staff))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    UUID.randomUUID(),
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    UUID otherJob = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            otherJob,
            UUID.randomUUID(),
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            AccountingSyncStatuses.COMPLETED,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            NOW,
            NOW,
            NOW));
    assertThatThrownBy(() -> service.syncStatus(owner, otherJob))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void notConfiguredAndProcessJobEdgeCases() {
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PH,
                    AccountingSystems.ZOHO_BOOKS,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNTING_NOT_CONFIGURED");
    service.processJob(UUID.randomUUID());
    UUID queued = UUID.randomUUID();
    jobs.insert(
        new AccountingSyncJob(
            queued,
            PH,
            AccountingSystems.ZOHO_BOOKS,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            AccountingSyncStatuses.QUEUED,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            NOW,
            null,
            null));
    service.processJob(queued);
    assertThat(jobs.findById(queued).orElseThrow().status())
        .isEqualTo(AccountingSyncStatuses.FAILED);

    ZohoBooksClientPort.SyncResult ok = ZohoBooksClientPort.SyncResult.ok("v1", true);
    ZohoBooksClientPort.SyncResult fail = ZohoBooksClientPort.SyncResult.fail("E", "m");
    assertThat(ok.created()).isTrue();
    assertThat(fail.errorCode()).isEqualTo("E");
  }

  private static AccountingVoucher sample(String type) {
    return new AccountingVoucher(
        UUID.randomUUID(),
        type,
        "V-1&<>\"",
        LocalDate.of(2026, 7, 10),
        "Party & Co",
        "29ABCDE1234F1Z5",
        100,
        18,
        118);
  }
}
