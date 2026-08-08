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
import com.nammamedmate.settings.application.PlatformConfigService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminPlatformConfigControllerTest {

  private PlatformConfigService service;
  private AdminPlatformConfigController controller;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    service = mock(PlatformConfigService.class);
    controller = new AdminPlatformConfigController(service);
    principal = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void listPatchGetDelegate() {
    when(service.list(principal, "payments")).thenReturn(Map.of("payments", Map.of()));
    when(service.list(principal, null)).thenReturn(Map.of());
    when(service.bulkUpdate(eq(principal), eq(Map.of("orders.delivery_fee", 30))))
        .thenReturn(Map.of("updated_count", 1));
    when(service.get(principal, "/orders.delivery_fee"))
        .thenReturn(Map.of("key", "orders.delivery_fee"));

    ApiResponse<Map<String, Object>> list = controller.list(principal, "payments");
    assertThat(list.data()).containsKey("payments");
    assertThat(controller.list(principal, null).success()).isTrue();
    assertThat(
            controller
                .bulkUpdate(principal, Map.of("orders.delivery_fee", 30))
                .data()
                .get("updated_count"))
        .isEqualTo(1);
    assertThat(controller.get(principal, "/orders.delivery_fee").data())
        .containsEntry("key", "orders.delivery_fee");
    verify(service).list(principal, "payments");
    verify(service).list(eq(principal), isNull());
  }
}
