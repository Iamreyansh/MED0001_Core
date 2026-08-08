package com.nammamedmate.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.FeatureFlagService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminFeatureFlagControllerTest {

  private FeatureFlagService service;
  private AdminFeatureFlagController controller;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    service = mock(FeatureFlagService.class);
    controller = new AdminFeatureFlagController(service);
    principal = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void listSummaryUpdateDelegate() {
    when(service.list(principal, "staging")).thenReturn(List.of(Map.of("name", "cod_enabled")));
    when(service.summary(principal)).thenReturn(Map.of("total", 1));
    when(service.update(eq(principal), eq("cod_enabled"), isNull(), eq(true), eq(100), eq("ok")))
        .thenReturn(Map.of("name", "cod_enabled"));
    when(service.update(eq(principal), eq("cod_enabled"), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("name", "cod_enabled"));

    ApiResponse<List<Map<String, Object>>> list = controller.list(principal, "staging");
    assertThat(list.data()).hasSize(1);
    assertThat(controller.summary(principal).data()).containsEntry("total", 1);

    var body = new AdminFeatureFlagController.UpdateRequest(true, 100, "ok");
    assertThat(controller.update(principal, "cod_enabled", null, body).success()).isTrue();
    assertThat(controller.update(principal, "cod_enabled", null, null).success()).isTrue();
    verify(service).update(principal, "cod_enabled", null, null, null, null);
  }
}
