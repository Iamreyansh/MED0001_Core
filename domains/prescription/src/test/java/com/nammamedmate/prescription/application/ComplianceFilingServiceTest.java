package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ActivityPage;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.GenerateJob;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ListPage;
import com.nammamedmate.prescription.application.port.out.InventoryBanPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.domain.ComplianceFiling;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class ComplianceFilingServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-01T03:30:00Z"); // 9 AM IST Jul 1
  private static final UUID FILING = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ADMIN = UUID.fromString("55555555-5555-4555-8555-555555555555");

  private ComplianceFilingStore store;
  private ScheduleDrugRegisterStore registerStore;
  private ComplianceExportStore exportStore;
  private InventoryBanPort inventoryBan;
  private NotificationDispatchPort notifications;
  private ComplianceFilingService service;

  private final MedmatePrincipal compliance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = mock(ComplianceFilingStore.class);
    registerStore = mock(ScheduleDrugRegisterStore.class);
    exportStore = mock(ComplianceExportStore.class);
    inventoryBan = mock(InventoryBanPort.class);
    notifications = mock(NotificationDispatchPort.class);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("https://s3/report.csv?ttl=86400");
    when(registerStore.listAll(any())).thenReturn(List.of());
    service =
        new ComplianceFilingService(
            store,
            registerStore,
            exportStore,
            inventoryBan,
            notifications,
            new ObjectMapper().findAndRegisterModules(),
            new InMemoryRateLimiter(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void acMonthlyJobCreatesH1AndXWithDue15th() {
    when(store.existsTypePeriod(anyString(), any(), any())).thenReturn(false);
    int n = service.createMonthlyFilings();
    assertThat(n).isEqualTo(2);
    ArgumentCaptor<ComplianceFiling> cap = ArgumentCaptor.forClass(ComplianceFiling.class);
    verify(store, times(2)).insert(cap.capture());
    assertThat(cap.getAllValues())
        .extracting(ComplianceFiling::filingType)
        .containsExactlyInAnyOrder("SCHEDULE_H1_REGISTER", "SCHEDULE_X_REGISTER");
    assertThat(cap.getAllValues().get(0).periodFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(cap.getAllValues().get(0).periodTo()).isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(cap.getAllValues().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
  }

  @Test
  void dueDateShiftsOffSundayAndHoliday() {
    assertThat(ComplianceFilingService.nextBusinessDay(LocalDate.of(2026, 3, 15)))
        .isEqualTo(LocalDate.of(2026, 3, 16)); // Sunday → Monday
    // 15 Aug 2026 is Saturday (holiday) then Sunday → Monday 17
    assertThat(ComplianceFilingService.nextBusinessDay(LocalDate.of(2026, 8, 15)))
        .isEqualTo(LocalDate.of(2026, 8, 17));
    assertThat(ComplianceFilingService.isNonBusinessDay(LocalDate.of(2026, 1, 26))).isTrue();
  }

  @Test
  void acOverdueJobTransitionsAndEmails() {
    ComplianceFiling pending = sampleFiling("PENDING", LocalDate.of(2026, 6, 15));
    when(store.findPendingPastDue(LocalDate.of(2026, 7, 1))).thenReturn(List.of(pending));
    when(store.findOverdueForEscalation(any())).thenReturn(List.of());
    assertThat(service.processOverdueFilings()).isEqualTo(1);
    ArgumentCaptor<ComplianceFiling> cap = ArgumentCaptor.forClass(ComplianceFiling.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo("OVERDUE");
    verify(notifications).notifyComplianceFilingOverdue(FILING, "SCHEDULE_H1_REGISTER", false);
  }

  @Test
  void acMarkFiledRequiresReferenceNumber() {
    assertThatThrownBy(
            () ->
                service.markFiled(
                    compliance, FILING, ADMIN, Instant.parse("2026-07-12T14:30:00Z"), "  "))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFERENCE_NUMBER_REQUIRED");
  }

  @Test
  void markFiledAlreadyFiled409() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(sampleFiling("FILED", LocalDate.of(2026, 7, 15))));
    assertThatThrownBy(
            () ->
                service.markFiled(
                    compliance, FILING, ADMIN, Instant.parse("2026-07-12T14:30:00Z"), "REF"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILING_ALREADY_FILED");
  }

  @Test
  void markFiledHappyPath() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(sampleFiling("PENDING", LocalDate.of(2026, 7, 15))));
    Map<String, Object> data =
        service.markFiled(
            compliance, FILING, ADMIN, Instant.parse("2026-07-12T14:30:00Z"), "KSDCD/1");
    assertThat(data.get("status")).isEqualTo("FILED");
    verify(store)
        .appendActivity(
            any(),
            any(),
            any(),
            eq(FILING),
            eq("FILING_MARKED"),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void acConcurrentGenerateReturnsSameJobId() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(sampleFiling("PENDING", LocalDate.of(2026, 7, 15))));
    UUID jobId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    GenerateJob generating =
        new GenerateJob(
            jobId, FILING, "CSV", "GENERATING", null, null, ADMIN, null, null, null, NOW);
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.of(generating));
    Map<String, Object> first = service.startGenerate(compliance, FILING, "2026-06", "CSV");
    Map<String, Object> second = service.startGenerate(compliance, FILING, "2026-06", "CSV");
    assertThat(first.get("job_id")).isEqualTo(jobId);
    assertThat(second.get("job_id")).isEqualTo(jobId);
    verify(store, never()).insertGenerateJob(any());
  }

  @Test
  void generateDedupesOnDuplicateKey() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(sampleFiling("PENDING", LocalDate.of(2026, 7, 15))));
    when(store.findGeneratingJob(FILING))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    FILING,
                    "CSV",
                    "GENERATING",
                    null,
                    null,
                    ADMIN,
                    null,
                    null,
                    null,
                    NOW)));
    org.mockito.Mockito.doThrow(new DuplicateKeyException("uq"))
        .when(store)
        .insertGenerateJob(any());
    Map<String, Object> data = service.startGenerate(compliance, FILING, "2026-06", "CSV");
    assertThat(data.get("job_id"))
        .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
  }

  @Test
  void generateAndPollReady() {
    ComplianceFiling filing = sampleFiling("PENDING", LocalDate.of(2026, 7, 15));
    when(store.findById(FILING)).thenReturn(Optional.of(filing));
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.empty());
    when(store.findGenerateJob(any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return Optional.of(
                  new GenerateJob(
                      id, FILING, "CSV", "GENERATING", null, null, ADMIN, null, null, null, NOW));
            })
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return Optional.of(
                  new GenerateJob(
                      id,
                      FILING,
                      "CSV",
                      "READY",
                      "reports/x.csv",
                      0,
                      ADMIN,
                      NOW,
                      NOW.plusSeconds(86400),
                      null,
                      NOW));
            });
    Map<String, Object> accepted = service.startGenerate(compliance, FILING, "2026-06", "CSV");
    UUID jobId = (UUID) accepted.get("job_id");
    when(store.findGenerateJob(jobId))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    jobId,
                    FILING,
                    "CSV",
                    "READY",
                    "reports/x.csv",
                    0,
                    ADMIN,
                    NOW,
                    NOW.plusSeconds(86400),
                    null,
                    NOW)));
    Map<String, Object> polled = service.pollGenerate(compliance, FILING, jobId);
    assertThat(polled.get("status")).isEqualTo("READY");
    assertThat(polled).containsKey("download_url");
  }

  @Test
  void acDrugRecallBansInventoryAndNotifies() {
    UUID pharmacy = UUID.fromString("22222222-2222-4222-8222-222222222222");
    when(inventoryBan.banByDrugNameAndBatch("Paracetamol 500mg", "PCM2024Q1"))
        .thenReturn(new InventoryBanPort.BanResult(3, List.of(pharmacy)));
    Map<String, Object> data =
        service.initiateDrugRecall(compliance, "Paracetamol 500mg", "PCM2024Q1", "CDSCO");
    assertThat(data.get("batches_banned")).isEqualTo(3);
    assertThat(data.get("pharmacies_affected")).isEqualTo(1);
    verify(notifications).notifyPharmacyDrugRecall(pharmacy, "Paracetamol 500mg", "PCM2024Q1");
    verify(store)
        .appendActivity(
            any(), any(), any(), any(), eq("DRUG_RECALLED"), any(), any(), any(), any(), any());
  }

  @Test
  void acActivityLogListsRxVerified() {
    when(store.listActivity(any()))
        .thenReturn(
            new ActivityPage(
                List.of(
                    Map.of(
                        "log_id",
                        UUID.randomUUID(),
                        "action",
                        "RX_VERIFIED",
                        "actor_id",
                        ADMIN,
                        "actor_role",
                        "admin_compliance")),
                1));
    var result = service.listActivity(ops, "RX_VERIFIED", null, null, null, 1, 50);
    assertThat(result.data()).hasSize(1);
    assertThat(result.data().get(0).get("action")).isEqualTo("RX_VERIFIED");
  }

  @Test
  void acFinanceCanListFilings() {
    when(store.list(any()))
        .thenReturn(
            new ListPage(List.of(sampleFiling("PENDING", LocalDate.of(2026, 7, 15))), 1, 1, 0));
    var result = service.listFilings(finance, null, null, 2026, false, 1, 20);
    assertThat(result.data().get("filings")).asList().hasSize(1);
    assertThatThrownBy(() -> service.startGenerate(finance, FILING, "2026-06", "CSV"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void acArchiveHidesFromDefaultListing() {
    when(store.archiveOlderThan(eq(LocalDate.of(2021, 7, 1)), any())).thenReturn(2);
    assertThat(service.archiveOldFilings()).isEqualTo(2);
    when(store.list(any())).thenReturn(new ListPage(List.of(), 0, 0, 0));
    var hidden = service.listFilings(compliance, null, null, 2020, false, 1, 20);
    assertThat(hidden.data().get("filings")).asList().isEmpty();
    ArgumentCaptor<ComplianceFilingStore.ListFilter> cap =
        ArgumentCaptor.forClass(ComplianceFilingStore.ListFilter.class);
    verify(store, times(1)).list(cap.capture());
    assertThat(cap.getValue().includeArchived()).isFalse();
  }

  @Test
  void listIncludeArchived() {
    when(store.list(any())).thenReturn(new ListPage(List.of(), 0, 0, 0));
    service.listFilings(compliance, "ADVERSE_EVENTS", "ALL", null, true, null, null);
    ArgumentCaptor<ComplianceFilingStore.ListFilter> cap =
        ArgumentCaptor.forClass(ComplianceFilingStore.ListFilter.class);
    verify(store).list(cap.capture());
    assertThat(cap.getValue().includeArchived()).isTrue();
    assertThat(cap.getValue().filingType()).isEqualTo("ADVERSE_EVENTS");
  }

  @Test
  void filingNotFoundAndPdfGenerate() {
    when(store.findById(FILING)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-06", "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILING_NOT_FOUND");

    when(store.findById(FILING))
        .thenReturn(Optional.of(sampleFiling("PENDING", LocalDate.of(2026, 7, 15))));
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.empty());
    when(store.findGenerateJob(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new GenerateJob(
                        inv.getArgument(0),
                        FILING,
                        "PDF",
                        "GENERATING",
                        null,
                        null,
                        ADMIN,
                        null,
                        null,
                        null,
                        NOW)));
    Map<String, Object> data = service.startGenerate(compliance, FILING, "2026-06", "PDF");
    assertThat(data.get("format")).isEqualTo("PDF");
    assertThat(new String(ComplianceFilingService.minimalPdf("hello"))).contains("%PDF");
  }

  @Test
  void schedulersDelegate() {
    when(store.existsTypePeriod(anyString(), any(), any())).thenReturn(true);
    new ComplianceFilingMonthlyScheduler(service).createMonthlyFilings();
    when(store.findPendingPastDue(any())).thenReturn(List.of());
    when(store.findOverdueForEscalation(any())).thenReturn(List.of());
    new ComplianceFilingOverdueScheduler(service).processOverdue();
    when(store.archiveOlderThan(any(), any())).thenReturn(0);
    new ComplianceFilingArchivalScheduler(service).archiveOldFilings();
  }

  private static ComplianceFiling sampleFiling(String status, LocalDate due) {
    return new ComplianceFiling(
        FILING,
        "SCHEDULE_H1_REGISTER",
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 30),
        due,
        status,
        null,
        null,
        null,
        "FILED".equals(status) ? ADMIN : null,
        "FILED".equals(status) ? NOW : null,
        "FILED".equals(status) ? "REF" : null,
        false,
        null,
        null,
        NOW,
        NOW);
  }
}
