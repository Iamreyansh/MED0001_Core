package com.nammamedmate.catalogue.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MedicineService;
import com.nammamedmate.catalogue.application.MedicineService.PageResult;
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
class AdminMedicineControllerTest {

  @Mock private MedicineService service;
  private AdminMedicineController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminMedicineController(service);
  }

  @Test
  void allEndpointsDelegate() {
    UUID id = UUID.randomUUID();
    when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageResult(Map.of("medicines", List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.summary(admin)).thenReturn(Map.of("total_skus", 0));
    when(service.create(any(), any())).thenReturn(Map.of("medicine_id", id.toString()));
    when(service.get(admin, id)).thenReturn(Map.of("medicine_id", id.toString()));
    when(service.update(any(), eq(id), any())).thenReturn(Map.of("updated_fields", List.of()));
    when(service.ban(any(), eq(id), any())).thenReturn(Map.of("is_banned", true));
    when(service.unban(any(), eq(id), any())).thenReturn(Map.of("is_banned", false));

    ApiResponse<Map<String, Object>> list =
        controller.list(admin, null, null, null, null, null, null, null, null, null, null);
    assertThat(list.success()).isTrue();
    assertThat(list.meta().hasNext()).isFalse();

    assertThat(controller.summary(admin).data()).containsEntry("total_skus", 0);

    controller.create(admin, null);
    controller.create(
        admin,
        new AdminMedicineController.CreateRequest(
            "n",
            "s",
            "m",
            id,
            "TABLET",
            10,
            "TABLET",
            "H",
            "30041090",
            12,
            218.50,
            true,
            "d",
            List.of(),
            9));

    controller.get(admin, id);
    controller.update(admin, id, null);
    controller.update(
        admin,
        id,
        new AdminMedicineController.UpdateRequest(
            "n", "d", null, null, 12, null, null, List.of(id), null));
    controller.ban(admin, id, null);
    controller.ban(admin, id, new AdminMedicineController.BanRequest("r"));
    controller.unban(admin, id, null);
    controller.unban(admin, id, new AdminMedicineController.BanRequest("r"));

    verify(service).get(admin, id);
    verify(service).ban(eq(admin), eq(id), isNull());
  }
}
