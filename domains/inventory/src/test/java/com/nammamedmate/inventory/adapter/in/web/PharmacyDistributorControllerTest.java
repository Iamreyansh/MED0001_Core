package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.DistributorService;
import com.nammamedmate.inventory.application.DistributorService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyDistributorControllerTest {

  @Mock private DistributorService service;
  private PharmacyDistributorController controller;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    controller = new PharmacyDistributorController(service);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  }

  @Test
  void delegatesAllEndpoints() {
    ListPage page =
        new ListPage(Map.of("distributors", java.util.List.of()), PaginationMeta.of(1, 20, 0));
    when(service.list(eq(owner), isNull(), isNull(), isNull(), isNull())).thenReturn(page);
    when(service.priceCompare(eq(owner), isNull(), isNull(), isNull(), isNull())).thenReturn(page);
    when(service.supplyList(eq(owner), any(), isNull(), isNull(), isNull())).thenReturn(page);
    when(service.create(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("id", "x"));
    when(service.patch(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("id", "x"));
    when(service.deactivate(any(), any())).thenReturn(Map.of("is_active", false));
    when(service.setPreferred(any(), any(), any())).thenReturn(Map.of("is_preferred_source", true));

    assertThat(controller.list(owner, null, null, null, null).get("success")).isEqualTo(true);
    assertThat(controller.priceCompare(owner, null, null, null, null).get("success"))
        .isEqualTo(true);
    UUID id = UUID.randomUUID();
    assertThat(controller.supplyList(owner, id, null, null, null).get("success")).isEqualTo(true);

    ResponseEntity<ApiResponse<Map<String, Object>>> created = controller.create(owner, null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    PharmacyDistributorController.DistributorRequest req =
        new PharmacyDistributorController.DistributorRequest(
            "Firm", null, "+919876543210", null, null, null, null, 0, null, true);
    assertThat(controller.create(owner, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.patch(owner, id, req).data()).containsKey("id");

    assertThat(controller.patch(owner, id, null).data()).containsKey("id");
    assertThat(controller.deactivate(owner, id).data().get("is_active")).isEqualTo(false);
    assertThat(
            controller.setPreferred(owner, id, UUID.randomUUID()).data().get("is_preferred_source"))
        .isEqualTo(true);
  }
}
