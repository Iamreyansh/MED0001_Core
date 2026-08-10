package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.GenerateJob;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ComplianceFilingServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-01T03:30:00Z");
  private static final UUID FILING = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ADMIN = UUID.fromString("55555555-5555-4555-8555-555555555555");

  private ComplianceFilingStore store;
  private ComplianceFilingService service;
  private NotificationDispatchPort notifications;
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = mock(ComplianceFilingStore.class);
    notifications = mock(NotificationDispatchPort.class);
    ComplianceExportStore exportStore = mock(ComplianceExportStore.class);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("u");
    ScheduleDrugRegisterStore registerStore = mock(ScheduleDrugRegisterStore.class);
    when(registerStore.listAll(any())).thenReturn(List.of());
    service =
        new ComplianceFilingService(
            store,
            registerStore,
            exportStore,
            mock(InventoryBanPort.class),
            notifications,
            new ObjectMapper().findAndRegisterModules(),
            new InMemoryRateLimiter(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void validationBranchesAndEscalation() {
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, null, "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILING_NOT_FOUND");
    when(store.findById(FILING))
        .thenReturn(
            Optional.of(
                new ComplianceFiling(
                    FILING,
                    "ADVERSE_EVENTS",
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 30),
                    LocalDate.of(2026, 7, 15),
                    "PENDING",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    NOW,
                    NOW)));
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
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-05", "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-06", "DOC"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    service.startGenerate(compliance, FILING, "2026-06", "CSV");

    UUID job = UUID.randomUUID();
    when(store.findGenerateJob(job))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    job, FILING, "CSV", "FAILED", null, null, ADMIN, null, null, "boom", NOW)));
    assertThat(service.pollGenerate(compliance, FILING, job).get("error_message"))
        .isEqualTo("boom");

    UUID other = UUID.randomUUID();
    when(store.findGenerateJob(job))
        .thenReturn(
            Optional.of(
                new GenerateJob(job, other, "CSV", "READY", "k", 1, ADMIN, NOW, NOW, null, NOW)));
    assertThatThrownBy(() -> service.pollGenerate(compliance, FILING, job))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    assertThatThrownBy(() -> service.markFiled(compliance, FILING, null, NOW, "R"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markFiled(compliance, FILING, ADMIN, null, "R"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.initiateDrugRecall(compliance, "", "B", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.initiateDrugRecall(compliance, "D", "", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findPendingPastDue(any())).thenReturn(List.of());
    ComplianceFiling overdue =
        new ComplianceFiling(
            FILING,
            "SCHEDULE_X_REGISTER",
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
            null,
            NOW,
            NOW);
    when(store.findOverdueForEscalation(eq(LocalDate.of(2026, 6, 28))))
        .thenReturn(List.of(overdue));
    service.processOverdueFilings();
    verify(notifications).notifyComplianceFilingOverdue(FILING, "SCHEDULE_X_REGISTER", true);

    assertThatThrownBy(() -> service.listFilings(compliance, "NOPE", null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void completeGenerateWhenFilingMissingFailsJob() {
    ComplianceFiling filing =
        new ComplianceFiling(
            FILING,
            "SCHEDULE_H1_REGISTER",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 7, 15),
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            NOW,
            NOW);
    when(store.findById(FILING)).thenReturn(Optional.of(filing)).thenReturn(Optional.empty());
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
    var data = service.startGenerate(compliance, FILING, "2026-06", "CSV");
    assertThat(data.get("status")).isEqualTo("GENERATING");
    verify(store)
        .updateGenerateJob(
            org.mockito.ArgumentMatchers.argThat(j -> j != null && "FAILED".equals(j.status())));
  }
}
