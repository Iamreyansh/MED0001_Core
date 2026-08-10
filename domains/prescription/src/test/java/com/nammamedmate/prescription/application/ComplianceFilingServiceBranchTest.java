package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComplianceFilingServiceBranchTest {

  private static final Instant NOW = Instant.parse("2026-07-01T03:30:00Z");
  private static final UUID FILING = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ADMIN = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @Test
  void remainingBranches() throws Exception {
    ComplianceFilingStore store = mock(ComplianceFilingStore.class);
    ScheduleDrugRegisterStore registerStore = mock(ScheduleDrugRegisterStore.class);
    ComplianceExportStore exportStore = mock(ComplianceExportStore.class);
    InventoryBanPort ban = mock(InventoryBanPort.class);
    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    ObjectMapper om = mock(ObjectMapper.class);
    when(om.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    RateLimiter limiter = mock(RateLimiter.class);
    when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("u");
    when(registerStore.listAll(any())).thenReturn(List.of());
    when(ban.banByDrugNameAndBatch(any(), any()))
        .thenReturn(new InventoryBanPort.BanResult(1, List.of(UUID.randomUUID())));

    ComplianceFilingService service =
        new ComplianceFilingService(
            store,
            registerStore,
            exportStore,
            ban,
            notifications,
            om,
            limiter,
            Clock.fixed(NOW, ZoneOffset.UTC));

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
    when(store.findById(FILING)).thenReturn(Optional.of(filing));
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
    // appendActivity JsonProcessingException path during generate complete
    service.startGenerate(compliance, FILING, "2026-06", "CSV");

    UUID job = UUID.randomUUID();
    when(store.findGenerateJob(job))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    job, FILING, "CSV", "READY", null, null, ADMIN, NOW, NOW, null, NOW)));
    assertThat(service.pollGenerate(compliance, FILING, job)).containsEntry("status", "READY");

    when(store.findGenerateJob(job))
        .thenReturn(
            Optional.of(
                new GenerateJob(
                    job, FILING, "CSV", "QUEUED", null, null, ADMIN, null, null, null, NOW)));
    assertThat(service.pollGenerate(compliance, FILING, job)).doesNotContainKey("download_url");

    when(store.list(any())).thenReturn(new ListPage(List.of(), 0, 0, 0));
    service.listFilings(compliance, "all", "pending", 2026, false, 1, 1);
    when(store.listActivity(any())).thenReturn(new ActivityPage(List.of(), 0));
    service.listActivity(compliance, "  ", null, null, null, null, null);
    service.listActivity(compliance, null, null, null, null, 1, 0);
    service.listActivity(compliance, null, null, null, null, 2, 250);
    assertThatThrownBy(
            () -> service.listActivity(compliance, null, null, "not-a-date", "2026-01-01", 1, 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.listActivity(compliance, null, null, "2026-01-01", "nope", 1, 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-06", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-06", "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, null, "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "  ", "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startGenerate(compliance, FILING, "2026-13", "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.initiateDrugRecall(compliance, null, "B", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.initiateDrugRecall(compliance, "D", null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.initiateDrugRecall(compliance, "D", "B", "   ");
    service.initiateDrugRecall(compliance, "D", "B", " recall ");

    assertThatThrownBy(() -> service.listFilings(null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.listFilings(compliance, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }
}
