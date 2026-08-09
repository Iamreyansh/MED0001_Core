package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.RackLocationService;
import com.nammamedmate.inventory.application.RackLocationService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyRackLocationControllerTest {

  @Mock private RackLocationService service;

  private PharmacyRackLocationController controller;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyRackLocationController(service);
  }

  @Test
  void listUnlocatedCreateAssignPrintDetailDelete() {
    when(service.list(any(), any(), any(), any(), any()))
        .thenReturn(new PageResult(Map.of("racks", List.of()), Map.of("page", 1)));
    Map<String, Object> list = controller.list(owner, null, null, 1, 50);
    assertThat(list.get("success")).isEqualTo(true);

    when(service.unlocated(any(), any(), any()))
        .thenReturn(new PageResult(Map.of("products", List.of()), Map.of("total", 0L)));
    assertThat(controller.unlocated(owner, 1, 20).get("success")).isEqualTo(true);

    when(service.create(any(), any(), any(), any())).thenReturn(Map.of("rack_code", "Z99-99"));
    ResponseEntity<ApiResponse<Map<String, Object>>> created =
        controller.create(
            owner, new PharmacyRackLocationController.CreateRequest("Z99-99", "Zone Z", null));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    controller.create(owner, null);

    when(service.assign(any(), any(), any())).thenReturn(Map.of("assigned_count", 1));
    assertThat(
            controller
                .assign(
                    owner,
                    new PharmacyRackLocationController.AssignRequest(
                        List.of(UUID.randomUUID()), "A1-01"))
                .success())
        .isTrue();
    controller.assign(owner, null);

    when(service.printLabels(any(), any())).thenReturn(Map.of("label_count", 2));
    assertThat(
            controller
                .printLabels(
                    owner,
                    new PharmacyRackLocationController.PrintLabelsRequest(
                        List.of("A1-01", "B2-03")))
                .success())
        .isTrue();
    controller.printLabels(owner, null);

    when(service.detail(any(), eq("A1-01"))).thenReturn(Map.of("rack_code", "A1-01"));
    assertThat(controller.detail(owner, "A1-01").data()).containsEntry("rack_code", "A1-01");

    when(service.delete(any(), eq("C3-07"))).thenReturn(Map.of("rack_code", "C3-07"));
    assertThat(controller.delete(owner, "C3-07").success()).isTrue();

    verify(service).create(eq(owner), isNull(), isNull(), isNull());
  }
}
