package com.nammamedmate.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.port.out.AuditArchivePort;
import com.nammamedmate.settings.application.port.out.AuditExportEmailPort;
import com.nammamedmate.settings.application.port.out.AuditExportJobStore;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.AuditLogRow;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.ListFilter;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogServiceTest {

  private PlatformAuditLogStore store;
  private AuditExportJobStore exportJobs;
  private AuditExportEmailPort exportEmail;
  private AuditArchivePort archivePort;
  private RateLimiter rateLimiter;
  private AuditLogService service;
  private MedmatePrincipal admin;
  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  @BeforeEach
  void setUp() {
    store = mock(PlatformAuditLogStore.class);
    exportJobs = mock(AuditExportJobStore.class);
    exportEmail = mock(AuditExportEmailPort.class);
    archivePort = mock(AuditArchivePort.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    Executor sync = Runnable::run;
    service =
        new AuditLogService(
            store,
            exportJobs,
            exportEmail,
            archivePort,
            rateLimiter,
            Clock.fixed(now, ZoneOffset.UTC),
            sync);
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void ac1_pharmacySuspendVisibleWithBeforeAfter() {
    UUID pharmacyId = Ids.newId();
    UUID entryId = Ids.newId();
    AuditLogRow row =
        new AuditLogRow(
            entryId,
            admin.subject(),
            "Ayesha",
            "admin_super",
            "ADMIN",
            "pharmacy.suspend",
            "pharmacy",
            pharmacyId,
            Map.of("status", "ACTIVE"),
            Map.of("status", "SUSPENDED"),
            Map.of("method", "PATCH"),
            "1.1.1.1",
            "ua",
            now);
    when(store.list(any())).thenReturn(new PageResult(List.of(row), 1));

    var result =
        service.list(
            admin,
            1,
            20,
            null,
            null,
            null,
            null,
            "pharmacy",
            pharmacyId,
            "pharmacy.suspend",
            null,
            null,
            false);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data = (List<Map<String, Object>>) result.data();
    assertThat(data).hasSize(1);
    assertThat(data.get(0).get("action")).isEqualTo("pharmacy.suspend");
    @SuppressWarnings("unchecked")
    Map<String, Object> before = (Map<String, Object>) data.get(0).get("before_state");
    @SuppressWarnings("unchecked")
    Map<String, Object> after = (Map<String, Object>) data.get(0).get("after_state");
    assertThat(before.get("status")).isEqualTo("ACTIVE");
    assertThat(after.get("status")).isEqualTo("SUSPENDED");
  }

  @Test
  void ac2_systemActorAppend() {
    service.appendSystem(
        "wallet-credit-job",
        "wallet.credit",
        "wallet",
        Ids.newId(),
        Map.of("balance", 0),
        Map.of("balance", 100));
    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    verify(store)
        .append(
            any(),
            isNull(),
            name.capture(),
            eq("SYSTEM"),
            type.capture(),
            eq("wallet.credit"),
            eq("wallet"),
            any(),
            any(),
            any(),
            any(),
            anyString(),
            isNull(),
            eq(now));
    assertThat(type.getValue()).isEqualTo("SYSTEM");
    assertThat(name.getValue()).isEqualTo("wallet-credit-job");

    service.appendSystem(null, null, null, null, null, null);
    service.appendSystem("  ", "act", "  ", Ids.newId(), Map.of(), Map.of());
    service.appendMiddleware(
        admin.subject(), "  ", "  ", "a", "t", null, Map.of(), "1.2.3.4", "ua");
    doThrow(new RuntimeException("db"))
        .when(store)
        .append(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any());
    service.appendSystem("j", "a", "r", Ids.newId(), Map.of(), Map.of());
  }

  @Test
  void ac3_dateRangeFilter() {
    when(store.list(any())).thenReturn(new PageResult(List.of(), 0));
    service.list(
        admin,
        1,
        20,
        "timestamp",
        "desc",
        null,
        null,
        null,
        null,
        null,
        "2026-07-01",
        "2026-07-24",
        false);
    ArgumentCaptor<ListFilter> cap = ArgumentCaptor.forClass(ListFilter.class);
    verify(store).list(cap.capture());
    assertThat(cap.getValue().from()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    assertThat(cap.getValue().to()).isBefore(Instant.parse("2026-07-25T00:00:00Z"));
    assertThat(cap.getValue().to()).isAfter(Instant.parse("2026-07-24T00:00:00Z"));
  }

  @Test
  void ac5_exportReturnsQueued() {
    var result =
        service.list(admin, null, null, null, null, null, null, null, null, null, null, null, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) result.data();
    assertThat(data.get("status")).isEqualTo("QUEUED");
    assertThat(data.get("export_job_id")).isNotNull();
    assertThat(result.meta()).isNull();
    verify(exportJobs).insertQueued(any(), any(), eq(now));
    verify(exportJobs).markCompleted(any(), anyString(), eq(now));
    verify(exportEmail).sendExportReady(eq(admin.subject()), any(), anyString());

    UUID pharmacyId = Ids.newId();
    service.list(
        admin,
        null,
        null,
        null,
        null,
        admin.subject(),
        "SYSTEM",
        "pharmacy",
        pharmacyId,
        "pharmacy.suspend",
        "2026-07-01T00:00:00Z",
        "2026-07-24T23:59:59Z",
        true);
  }

  @Test
  void ac6_passwordRedactedOnAppendSystem() {
    service.appendSystem(
        "job", "x", "y", Ids.newId(), Map.of("password", "secret"), Map.of("password_hash", "h"));
    ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
    verify(store)
        .append(
            any(),
            isNull(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            before.capture(),
            after.capture(),
            any(),
            anyString(),
            isNull(),
            eq(now));
    assertThat(before.getValue().get("password")).isEqualTo("[REDACTED]");
    assertThat(after.getValue().get("password_hash")).isEqualTo("[REDACTED]");
  }

  @Test
  void ac7_detailIncludesDiff() {
    UUID id = Ids.newId();
    when(store.findById(id))
        .thenReturn(
            Optional.of(
                new AuditLogRow(
                    id,
                    admin.subject(),
                    "A",
                    "admin_super",
                    "ADMIN",
                    "pharmacy.suspend",
                    "pharmacy",
                    Ids.newId(),
                    Map.of("status", "ACTIVE"),
                    Map.of("status", "SUSPENDED", "suspended_reason", "x"),
                    Map.of(),
                    "0.0.0.0",
                    null,
                    now)));
    Map<String, Object> data = service.get(admin, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diff = (List<Map<String, Object>>) data.get("diff");
    assertThat(diff).isNotEmpty();
    assertThat(diff).anyMatch(o -> "/status".equals(o.get("path")));
  }

  @Test
  void validationAndAuthBranches() {
    assertThatThrownBy(
            () ->
                service.list(
                    null, 1, 20, null, null, null, null, null, null, null, null, null, false))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.list(
                    customer, 1, 20, null, null, null, null, null, null, null, null, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(
            () ->
                service.list(
                    admin, 1, 20, null, null, null, "NOPE", null, null, null, null, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.list(
                    admin, 1, 20, "bad", null, null, null, null, null, null, null, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.list(
                    admin,
                    1,
                    20,
                    "timestamp",
                    "sideways",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.list(
                    admin,
                    1,
                    20,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "2026-07-24",
                    "2026-07-01",
                    false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.list(
                    admin,
                    1,
                    20,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "not-a-date",
                    null,
                    false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(5);
    assertThatThrownBy(
            () ->
                service.list(
                    admin, 1, 20, null, null, null, null, null, null, null, null, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void getNotFoundAndMiddlewareArchive() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    assertThatThrownBy(() -> service.get(admin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("AUDIT_LOG_NOT_FOUND");
    when(store.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(admin, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("AUDIT_LOG_NOT_FOUND");

    service.appendMiddleware(
        admin.subject(),
        "A",
        "admin_super",
        "staff.update",
        "staff",
        Ids.newId(),
        Map.of(),
        " ",
        "ua");
    service.appendMiddleware(null, null, null, "x", "y", null, null, null, null);
    doThrow(new RuntimeException("x"))
        .when(store)
        .append(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any());
    service.appendMiddleware(admin.subject(), "A", "r", "a", "t", null, Map.of(), "1.1.1.1", null);

    UUID oldId = Ids.newId();
    when(store.listForArchive(any(), anyInt()))
        .thenReturn(
            List.of(
                new AuditLogRow(
                    oldId,
                    null,
                    "system",
                    "SYSTEM",
                    "SYSTEM",
                    "x",
                    "y",
                    null,
                    null,
                    null,
                    null,
                    "0.0.0.0",
                    null,
                    now.minusSeconds(10))));
    service.archiveOlderThanTwoYears();
    verify(archivePort).archive(eq(oldId), any());
    verify(store).markArchived(eq(oldId), eq(now));

    doThrow(new RuntimeException("arch")).when(archivePort).archive(any(), any());
    service.archiveOlderThanTwoYears();

    doThrow(new RuntimeException("export")).when(exportJobs).markCompleted(any(), any(), any());
    service.list(admin, null, null, null, null, null, null, null, null, null, null, null, true);

    when(store.list(any())).thenReturn(new PageResult(List.of(), 0));
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service.list(
        admin,
        1,
        10,
        "action",
        "asc",
        admin.subject(),
        "ADMIN",
        "pharmacy",
        Ids.newId(),
        "pharmacy.suspend",
        "2026-07-24T00:00:00Z",
        "2026-07-24T23:59:59Z",
        false);
    verify(store, org.mockito.Mockito.atLeastOnce()).list(any());
  }

  @Test
  void exportCompleteSwallowsEmailFailure() {
    doThrow(new RuntimeException("mail"))
        .when(exportEmail)
        .sendExportReady(any(), any(), anyString());
    var result =
        service.list(admin, null, null, null, null, null, null, null, null, null, null, null, true);
    assertThat(((Map<?, ?>) result.data()).get("status")).isEqualTo("QUEUED");
    verify(exportJobs).insertQueued(any(), any(), any());
  }

  @Test
  void blankFiltersAndPartialDateBounds() {
    when(store.list(any())).thenReturn(new PageResult(List.of(), 0));
    service.list(admin, 1, 20, "  ", "  ", null, "  ", "  ", null, "  ", null, "2026-07-24", false);
    service.list(
        admin,
        1,
        20,
        "timestamp",
        "asc",
        null,
        null,
        null,
        null,
        null,
        "2026-07-01T00:00:00Z",
        null,
        Boolean.FALSE);
    service.list(admin, 1, 20, null, null, null, null, null, null, null, "  ", "  ", false);
    ArgumentCaptor<ListFilter> cap = ArgumentCaptor.forClass(ListFilter.class);
    verify(store, org.mockito.Mockito.atLeast(2)).list(cap.capture());
  }

  @Test
  void allAdminRolesAndNullActorListItem() {
    when(store.list(any()))
        .thenReturn(
            new PageResult(
                List.of(
                    new AuditLogRow(
                        Ids.newId(),
                        null,
                        "system",
                        "SYSTEM",
                        "SYSTEM",
                        "wallet.credit",
                        "wallet",
                        null,
                        null,
                        Map.of("ok", 1),
                        null,
                        "0.0.0.0",
                        null,
                        now)),
                1));
    for (AuthRole role :
        List.of(
            AuthRole.ADMIN_SUPER,
            AuthRole.ADMIN_OPERATIONS,
            AuthRole.ADMIN_FINANCE,
            AuthRole.ADMIN_SUPPORT,
            AuthRole.ADMIN_COMPLIANCE)) {
      MedmatePrincipal p = new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "j");
      var result =
          service.list(p, 1, 20, null, null, null, null, null, null, null, null, null, false);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> data = (List<Map<String, Object>>) result.data();
      assertThat(data.get(0).get("resource_id")).isNull();
      @SuppressWarnings("unchecked")
      Map<String, Object> actor = (Map<String, Object>) data.get(0).get("actor");
      assertThat(actor.get("id")).isNull();
    }
  }
}
