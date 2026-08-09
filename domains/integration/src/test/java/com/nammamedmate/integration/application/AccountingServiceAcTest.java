package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.adapter.out.client.StubZohoBooksClient;
import com.nammamedmate.integration.adapter.out.persistence.LocalAccountingExportObjectStore;
import com.nammamedmate.integration.application.port.out.AccountingDataPort;
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
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountingServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:38:00Z");
  private static final UUID PHARMACY = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final byte[] AES_KEY = new byte[32];

  @TempDir java.nio.file.Path tempDir;

  private InMemoryStores.AccountingIntegrations integrations;
  private InMemoryStores.AccountingJobs jobs;
  private ListAccountingData data;
  private StubZohoBooksClient zoho;
  private AccountingService service;
  private MedmatePrincipal owner;
  private boolean planAllowed = true;

  @BeforeEach
  void setUp() throws Exception {
    integrations = new InMemoryStores.AccountingIntegrations();
    jobs = new InMemoryStores.AccountingJobs();
    data = new ListAccountingData();
    zoho = new StubZohoBooksClient(Clock.fixed(NOW, ZoneOffset.UTC));
    AesGcmCipher cipher = new AesGcmCipher(AES_KEY);
    service =
        new AccountingService(
            integrations,
            jobs,
            data,
            zoho,
            new LocalAccountingExportObjectStore(tempDir, "file://" + tempDir),
            pharmacyId -> planAllowed,
            cipher,
            Clock.fixed(NOW, ZoneOffset.UTC));
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void ac001_freePlanSyncReturnsPlanUpgradeRequired() {
    planAllowed = false;
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PHARMACY,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_UPGRADE_REQUIRED");
  }

  @Test
  void ac002_concurrentSyncReturnsSyncInProgress() {
    connectTally();
    jobs.insert(
        new AccountingSyncJob(
            UUID.randomUUID(),
            PHARMACY,
            AccountingSystems.TALLY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            AccountingSyncStatuses.RUNNING,
            0,
            0,
            0,
            List.of(),
            AccountingTriggeredBy.MANUAL,
            NOW,
            NOW,
            null));
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PHARMACY,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SYNC_IN_PROGRESS");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_syncStatusShowsRecordsFailedAndErrors() {
    service.connectZoho(
        PHARMACY, "60012345678", "Apollo", "access", "refresh", NOW.plusSeconds(3600));
    UUID ok = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID bad1 = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID bad2 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    data.sales.add(voucher(ok, "INV-1", "Customer", "29ABCDE1234F1Z5"));
    data.sales.add(voucher(bad1, "INV-2", "Bad1", "27INVALID123"));
    data.sales.add(voucher(bad2, "INV-3", "Bad2", "27INVALID999"));

    Map<String, Object> queued =
        service.triggerSync(
            owner,
            PHARMACY,
            AccountingSystems.ZOHO_BOOKS,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31));
    UUID jobId = UUID.fromString(queued.get("job_id").toString());
    Map<String, Object> status = service.syncStatus(owner, jobId);
    assertThat(status.get("records_failed")).isEqualTo(2);
    assertThat(status.get("records_synced")).isEqualTo(1);
    List<Map<String, Object>> errors = (List<Map<String, Object>>) status.get("errors");
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).get("error_code")).isEqualTo("INVALID_CUSTOMER_GSTIN");
    assertThat(errors).anyMatch(e -> bad1.toString().equals(e.get("record_id")));
    assertThat(errors).anyMatch(e -> bad2.toString().equals(e.get("record_id")));
  }

  @Test
  void ac004_exportTallyXmlHasEnvelopeImportData() throws Exception {
    connectTally();
    data.sales.add(
        voucher(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "INV-T1",
            "Walk-in",
            "29ABCDE1234F1Z5"));
    Map<String, Object> export =
        service.exportTallyXml(
            owner,
            PHARMACY,
            AccountingSyncTypes.SALES,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31));
    assertThat(export.get("records_count")).isEqualTo(1);
    assertThat(export.get("download_url").toString()).contains("tally_xml_");
    String url = export.get("download_url").toString();
    String fileName = url.substring(url.lastIndexOf('/') + 1, url.indexOf('?'));
    String xml = Files.readString(tempDir.resolve(fileName));
    assertThat(xml).contains("<ENVELOPE>").contains("<IMPORTDATA>").contains("<VOUCHER");
  }

  @Test
  void ac005_dailyAutoSyncSetsNextSyncAt0200Ist() {
    connectTally();
    Map<String, Object> patched =
        service.patchConfig(owner, AccountingSystems.TALLY, true, AccountingSyncFrequencies.DAILY);
    Instant expected =
        NextSyncAtCalculator.next(
            AccountingSyncFrequencies.DAILY, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(patched.get("next_sync_at")).isEqualTo(expected.toString());
    assertThat(expected.atZone(NextSyncAtCalculator.IST).toLocalTime().getHour()).isEqualTo(2);
  }

  @Test
  void ac006_zohoTokenRefreshBeforeExpiryDuringSync() {
    service.connectZoho(
        PHARMACY, "60012345678", "Apollo", "old-access", "refresh-token", NOW.plusSeconds(60));
    data.sales.add(
        voucher(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "INV-R1",
            "Cust",
            "29ABCDE1234F1Z5"));
    service.triggerSync(
        owner,
        PHARMACY,
        AccountingSystems.ZOHO_BOOKS,
        AccountingSyncTypes.SALES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31));
    assertThat(zoho.refreshCount()).isEqualTo(1);
    AccountingIntegration cfg = integrations.findByPharmacyId(PHARMACY).orElseThrow();
    assertThat(cfg.zohoTokenExpiresAt()).isAfter(NOW.plusSeconds(60));
  }

  @Test
  void ac007_sameInvoiceTwiceCreatesOneZohoVoucher() {
    service.connectZoho(
        PHARMACY, "60012345678", "Apollo", "access", "refresh", NOW.plusSeconds(3600));
    UUID invoice = UUID.fromString("77777777-7777-4777-8777-777777777777");
    data.sales.add(voucher(invoice, "INV-D1", "Cust", "29ABCDE1234F1Z5"));
    service.triggerSync(
        owner,
        PHARMACY,
        AccountingSystems.ZOHO_BOOKS,
        AccountingSyncTypes.SALES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31));
    service.triggerSync(
        owner,
        PHARMACY,
        AccountingSystems.ZOHO_BOOKS,
        AccountingSyncTypes.SALES,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31));
    assertThat(zoho.voucherCount()).isEqualTo(1);
  }

  @Test
  void ac008_configDisconnectedWhenNotConnected() {
    Map<String, Object> cfg = service.getConfig(owner);
    assertThat(cfg.get("connected_system")).isNull();
    assertThat(cfg.get("api_key_status")).isEqualTo(AccountingApiKeyStatuses.DISCONNECTED);
  }

  @Test
  void anonymousRejectedAndInvalidPeriod() {
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    null,
                    PHARMACY,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    connectTally();
    assertThatThrownBy(
            () ->
                service.triggerSync(
                    owner,
                    PHARMACY,
                    AccountingSystems.TALLY,
                    AccountingSyncTypes.SALES,
                    LocalDate.of(2026, 7, 31),
                    LocalDate.of(2026, 7, 1)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void autoSyncSchedulerQueuesDailySales() {
    connectTally();
    service.patchConfig(owner, AccountingSystems.TALLY, true, AccountingSyncFrequencies.DAILY);
    AccountingIntegration cfg = integrations.findByPharmacyId(PHARMACY).orElseThrow();
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
            true,
            AccountingSyncFrequencies.DAILY,
            NOW.minusSeconds(10),
            cfg.lastSyncAt(),
            cfg.lastSyncStatus(),
            cfg.createdAt(),
            cfg.updatedAt()));
    service.runDueAutoSyncs();
    service.processQueuedJobs();
    assertThat(jobs.findQueued(10)).isEmpty();
  }

  private void connectTally() {
    service.patchConfig(owner, AccountingSystems.TALLY, false, null);
  }

  private static AccountingVoucher voucher(UUID id, String number, String party, String gstin) {
    return new AccountingVoucher(
        id, "SALES_INVOICE", number, LocalDate.of(2026, 7, 15), party, gstin, 10000, 1800, 11800);
  }

  private static final class ListAccountingData implements AccountingDataPort {
    final List<AccountingVoucher> sales = new ArrayList<>();
    final List<AccountingVoucher> purchases = new ArrayList<>();
    final List<AccountingVoucher> expenses = new ArrayList<>();
    final List<AccountingVoucher> gst = new ArrayList<>();

    @Override
    public List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to) {
      return List.copyOf(sales);
    }

    @Override
    public List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to) {
      return List.copyOf(purchases);
    }

    @Override
    public List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to) {
      return List.copyOf(expenses);
    }

    @Override
    public List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to) {
      return List.copyOf(gst);
    }
  }
}
