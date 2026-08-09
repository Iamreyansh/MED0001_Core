package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.AccountingService;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AccountingControllersTest {

  private AccountingService service;
  private AccountingIntegrationController controller;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    service = mock(AccountingService.class);
    controller = new AccountingIntegrationController(service);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  }

  @Test
  void anonymousRejectedOnAllEndpoints() {
    assertThatThrownBy(() -> controller.sync(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.syncStatus(null, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.getConfig(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.patchConfig(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                controller.exportTallyXml(
                    null, UUID.randomUUID(), "SALES", LocalDate.now(), LocalDate.now()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void syncReturns202AndDelegates() {
    when(service.triggerSync(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "QUEUED"));
    var response =
        controller.sync(
            owner,
            new AccountingIntegrationController.SyncRequest(
                owner.pharmacyId(),
                "ZOHO_BOOKS",
                "SALES",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31)));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().data().get("status")).isEqualTo("QUEUED");
    controller.sync(owner, null);
  }

  @Test
  void configStatusExportHappy() {
    Map<String, Object> disconnected = new HashMap<>();
    disconnected.put("connected_system", null);
    when(service.getConfig(owner)).thenReturn(disconnected);
    when(service.patchConfig(any(), any(), any(), any()))
        .thenReturn(Map.of("auto_sync_enabled", true));
    when(service.syncStatus(eq(owner), any())).thenReturn(Map.of("records_failed", 0));
    when(service.exportTallyXml(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("records_count", 1));

    assertThat(controller.getConfig(owner).data().get("connected_system")).isNull();
    assertThat(
            controller
                .patchConfig(
                    owner,
                    new AccountingIntegrationController.PatchConfigRequest("TALLY", true, "DAILY"))
                .data()
                .get("auto_sync_enabled"))
        .isEqualTo(true);
    controller.patchConfig(owner, null);
    UUID jobId = UUID.randomUUID();
    assertThat(controller.syncStatus(owner, jobId).data().get("records_failed")).isEqualTo(0);
    assertThat(
            controller
                .exportTallyXml(
                    owner,
                    owner.pharmacyId(),
                    "SALES",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31))
                .data()
                .get("records_count"))
        .isEqualTo(1);
    verify(service).syncStatus(owner, jobId);
  }
}
