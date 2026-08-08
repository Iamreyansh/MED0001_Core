package com.nammamedmate.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.AuditLogService;
import com.nammamedmate.settings.application.AuditLogService.ListResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminAuditLogControllerTest {

  private AuditLogService service;
  private AdminAuditLogController controller;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    service = mock(AuditLogService.class);
    controller = new AdminAuditLogController(service);
    principal = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void listAndGetDelegate() {
    when(service.list(
            eq(principal),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(new ListResult(List.of(Map.of("id", "1")), PaginationMeta.of(1, 20, 1)));
    when(service.list(
            eq(principal),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(true)))
        .thenReturn(new ListResult(Map.of("status", "QUEUED"), null));

    ApiResponse<?> list =
        controller.list(
            principal, 1, 20, null, null, null, null, null, null, null, null, null, false);
    assertThat(list.success()).isTrue();
    assertThat(list.meta()).isNotNull();

    ApiResponse<?> export =
        controller.list(
            principal, null, null, null, null, null, null, null, null, null, null, null, true);
    assertThat(export.meta()).isNull();
    assertThat(((Map<?, ?>) export.data()).get("status")).isEqualTo("QUEUED");

    when(service.get(principal, principal.subject()))
        .thenReturn(Map.of("id", "x", "diff", List.of()));
    assertThat(controller.get(principal, principal.subject()).data()).containsKey("diff");
  }
}
