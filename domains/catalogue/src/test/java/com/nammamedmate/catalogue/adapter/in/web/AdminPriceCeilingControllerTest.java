package com.nammamedmate.catalogue.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.PriceCeilingService;
import com.nammamedmate.catalogue.application.PriceCeilingService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPriceCeilingControllerTest {

  @Mock private PriceCeilingService service;
  private AdminPriceCeilingController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminPriceCeilingController(service);
  }

  @Test
  void allEndpointsDelegate() {
    UUID id = UUID.randomUUID();
    when(service.listCeilings(any(), any(), any(), any(), any()))
        .thenReturn(
            new PageResult(Map.of("price_ceilings", List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.setCeiling(any(), eq(id), any(), any(), any()))
        .thenReturn(Map.of("medicine_id", id.toString()));
    when(service.removeCeiling(any(), eq(id), any())).thenReturn(Map.of("ceiling_removed", true));
    when(service.listViolations(any(), any(), any(), any(), any()))
        .thenReturn(new PageResult(Map.of("violations", List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.notifyViolations(any(), any(), any()))
        .thenReturn(Map.of("pharmacies_notified", 0));

    ApiResponse<Map<String, Object>> list = controller.listCeilings(admin, null, true, 1, 20);
    assertThat(list.success()).isTrue();

    controller.setCeiling(admin, id, null);
    controller.setCeiling(
        admin, id, new AdminPriceCeilingController.SetCeilingRequest(72.0, "2026-07-01", "NLEM"));
    verify(service).setCeiling(admin, id, 72.0, "2026-07-01", "NLEM");

    controller.removeCeiling(admin, id, null);
    controller.removeCeiling(
        admin, id, new AdminPriceCeilingController.RemoveCeilingRequest("lifted"));
    verify(service).removeCeiling(admin, id, "lifted");

    controller.listViolations(admin, id, null, null, null);
    controller.notifyViolations(admin, null);
    controller.notifyViolations(
        admin, new AdminPriceCeilingController.NotifyRequest(id, "please lower"));
    verify(service).notifyViolations(admin, id, "please lower");
    verify(service).notifyViolations(eq(admin), isNull(), isNull());
  }
}
