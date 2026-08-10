package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.prescription.application.ComplianceFilingService.ActivityResult;
import com.nammamedmate.prescription.application.ComplianceFilingService.ListResult;
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

class ComplianceFilingServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-01T03:30:00Z");
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

  @BeforeEach
  void setUp() {
    store = mock(ComplianceFilingStore.class);
    registerStore = mock(ScheduleDrugRegisterStore.class);
    exportStore = mock(ComplianceExportStore.class);
    inventoryBan = mock(InventoryBanPort.class);
    notifications = mock(NotificationDispatchPort.class);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("https://dl");
    when(registerStore.listAll(any())).thenReturn(List.of());
    when(inventoryBan.banByDrugNameAndBatch(any(), any()))
        .thenReturn(new InventoryBanPort.BanResult(0, null));
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
  void recordNullCopiesAndListDefaults() {
    assertThat(new ListResult(null, PaginationMeta.of(1, 20, 0)).data()).isEmpty();
    assertThat(new ActivityResult(null, PaginationMeta.of(1, 50, 0)).data()).isEmpty();
    assertThat(new ListPage(null, 0, 0, 0).filings()).isEmpty();
    assertThat(new ActivityPage(null, 0).items()).isEmpty();
    assertThat(new InventoryBanPort.BanResult(0, null).pharmacyIds()).isEmpty();

    when(store.list(any())).thenReturn(new ListPage(List.of(), 0, 0, 0));
    service.listFilings(compliance, "ALL", "ALL", null, null, 0, 500);
    when(store.listActivity(any())).thenReturn(new ActivityPage(List.of(), 0));
    service.listActivity(compliance, "ALL", null, "  ", "  ", 0, 500);
    service.listActivity(compliance, null, null, "2026-01-01", "2026-01-31", 1, 10);
  }

  @Test
  void pollCompletesGeneratingAndExpiresDefault() {
    UUID jobId = UUID.randomUUID();
    when(store.findGenerateJob(jobId))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    jobId, FILING, "CSV", "GENERATING", null, null, ADMIN, null, null, null, NOW)))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    jobId,
                    FILING,
                    "CSV",
                    "READY",
                    "reports/x.csv",
                    2,
                    ADMIN,
                    NOW,
                    null,
                    null,
                    NOW)));
    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_X_REGISTER", "PENDING", null)));
    Map<String, Object> data = service.pollGenerate(compliance, FILING, jobId);
    assertThat(data.get("status")).isEqualTo("READY");
    assertThat(data.get("expires_at")).isNotNull();

    when(store.findGenerateJob(jobId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pollGenerate(compliance, FILING, jobId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void pollGeneratingDisappearsAfterComplete() {
    UUID jobId = UUID.randomUUID();
    when(store.findGenerateJob(jobId))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    jobId, FILING, "CSV", "GENERATING", null, null, ADMIN, null, null, null, NOW)))
        .thenReturn(Optional.empty());
    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_H1_REGISTER", "PENDING", null)));
    assertThatThrownBy(() -> service.pollGenerate(compliance, FILING, jobId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void generateRuntimeFailureAndEarlyReturn() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_H1_REGISTER", "PENDING", null)));
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.empty());
    when(store.findGenerateJob(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new GenerateJob(
                        inv.getArgument(0),
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
    when(registerStore.listAll(any())).thenThrow(new RuntimeException());
    service.startGenerate(compliance, FILING, "2026-06", "CSV");
    verify(store)
        .updateGenerateJob(
            org.mockito.ArgumentMatchers.argThat(
                j ->
                    j != null
                        && "FAILED".equals(j.status())
                        && "generate failed".equals(j.errorMessage())));

    UUID readyId = UUID.randomUUID();
    when(store.findGenerateJob(readyId))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    readyId, FILING, "CSV", "READY", "k", 1, ADMIN, NOW, NOW, null, NOW)));
    // completeGenerateJob early-return via poll when already READY
    assertThat(service.pollGenerate(compliance, FILING, readyId).get("status")).isEqualTo("READY");
  }

  @Test
  void generateWithMessageAndListWithReportUrl() {
    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_H1_REGISTER", "PENDING", "reports/old.csv")));
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.empty());
    when(store.findGenerateJob(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new GenerateJob(
                        inv.getArgument(0),
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
    doThrow(new RuntimeException("boom")).when(exportStore).put(any(), any(), any());
    service.startGenerate(compliance, FILING, "2026-06", "CSV");
    verify(store)
        .updateGenerateJob(
            org.mockito.ArgumentMatchers.argThat(
                j -> j != null && "boom".equals(j.errorMessage())));

    when(store.list(any()))
        .thenReturn(
            new ListPage(
                List.of(filing("SCHEDULE_H1_REGISTER", "PENDING", "reports/old.csv")), 1, 1, 0));
    var listed = service.listFilings(compliance, null, null, 2026, false, 1, 20);
    assertThat(((List<?>) listed.data().get("filings")).get(0))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("generated_report_url", "https://dl");
  }

  @Test
  void escalationSkipAndMonthlyIdempotentAndPdfTruncate() {
    when(store.findPendingPastDue(any())).thenReturn(List.of());
    ComplianceFiling already =
        new ComplianceFiling(
            FILING,
            "SCHEDULE_H1_REGISTER",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 28),
            "OVERDUE",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            NOW,
            NOW,
            NOW,
            NOW);
    when(store.findOverdueForEscalation(any())).thenReturn(List.of(already));
    service.processOverdueFilings();
    verify(store, never()).setOverdueEscalation(any(), any());

    when(store.existsTypePeriod(any(), any(), any())).thenReturn(true);
    assertThat(service.createMonthlyFilings()).isZero();

    String big = "x".repeat(9000);
    assertThat(ComplianceFilingService.minimalPdf(big)).isNotEmpty();
    assertThat(ComplianceFilingService.minimalPdf(null)).isNotEmpty();

    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_X_REGISTER", "PENDING", null)));
    when(store.findGeneratingJob(FILING)).thenReturn(Optional.empty());
    when(store.findGenerateJob(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new GenerateJob(
                        inv.getArgument(0),
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
    service.startGenerate(compliance, FILING, "2026-06", "CSV");

    service.initiateDrugRecall(compliance, "Drug", "BATCH1", null);
    verify(store)
        .insert(org.mockito.ArgumentMatchers.argThat(f -> "DRUG_RECALL".equals(f.filingType())));
  }

  @Test
  void schedulersLogWhenWorkDone() {
    when(store.existsTypePeriod(any(), any(), any())).thenReturn(false);
    new ComplianceFilingMonthlyScheduler(service).createMonthlyFilings();
    when(store.findPendingPastDue(any()))
        .thenReturn(List.of(filing("SCHEDULE_H1_REGISTER", "PENDING", null)));
    when(store.findOverdueForEscalation(any())).thenReturn(List.of());
    new ComplianceFilingOverdueScheduler(service).processOverdue();
    when(store.archiveOlderThan(any(), any())).thenReturn(3);
    new ComplianceFilingArchivalScheduler(service).archiveOldFilings();
  }

  @Test
  void markFiledNullReference() {
    assertThatThrownBy(() -> service.markFiled(compliance, FILING, ADMIN, NOW, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFERENCE_NUMBER_REQUIRED");
  }

  @Test
  void completeGenerateJobNullJob() {
    UUID jobId = UUID.randomUUID();
    when(store.findGenerateJob(jobId)).thenReturn(Optional.empty());
    when(store.findById(FILING))
        .thenReturn(Optional.of(filing("SCHEDULE_H1_REGISTER", "PENDING", null)));
    when(store.findGeneratingJob(FILING))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    jobId, FILING, "CSV", "GENERATING", null, null, ADMIN, null, null, null, NOW)));
    // returns existing generating without completing
    assertThat(service.startGenerate(compliance, FILING, "2026-06", "CSV").get("job_id"))
        .isEqualTo(jobId);
  }

  private static ComplianceFiling filing(String type, String status, String key) {
    return new ComplianceFiling(
        FILING,
        type,
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 30),
        LocalDate.of(2026, 7, 15),
        status,
        key,
        key == null ? null : "CSV",
        key == null ? null : NOW,
        null,
        null,
        null,
        false,
        null,
        null,
        NOW,
        NOW);
  }
}
