package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.InventoryProductService;
import com.nammamedmate.inventory.application.InventoryProductService.ExcelExport;
import com.nammamedmate.inventory.application.InventoryProductService.ListPage;
import com.nammamedmate.inventory.application.RackLocationService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyInventoryControllerTest {

  @Mock private InventoryProductService service;
  @Mock private RackLocationService rackLocationService;

  private PharmacyInventoryController controller;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyInventoryController(service, rackLocationService);
  }

  @Test
  void listJson() {
    when(service.list(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListPage(Map.of("products", List.of()), Map.of("page", 1)));

    Object body = controller.list(owner, "ALL", null, null, null, 1, 20, null, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) body;
    assertThat(map.get("success")).isEqualTo(true);

    controller.list(owner, "ALL", null, null, null, 1, 20, "", null);
  }

  @Test
  void listExcelExport() {
    when(service.exportExcel(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ExcelExport(
                new byte[] {1, 2},
                "inventory-export.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    Object body = controller.list(owner, null, null, null, null, null, null, "EXCEL", null);

    assertThat(body).isInstanceOf(ResponseEntity.class);
    @SuppressWarnings("unchecked")
    ResponseEntity<byte[]> res = (ResponseEntity<byte[]>) body;
    assertThat(res.getBody()).containsExactly(1, 2);
  }

  @Test
  void listPdfRejected() {
    assertThatThrownBy(
            () -> controller.list(owner, null, null, null, null, null, null, "PDF", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void summaryGetPatch() {
    when(service.summary(owner)).thenReturn(Map.of("total_skus", 1));
    assertThat(controller.summary(owner).data()).containsEntry("total_skus", 1);

    UUID id = UUID.randomUUID();
    when(service.get(owner, id)).thenReturn(Map.of("id", id.toString()));
    assertThat(controller.get(owner, id).data()).containsEntry("id", id.toString());

    when(service.patchSettings(any(), eq(id), any(), any(), any(), any()))
        .thenReturn(Map.of("id", id.toString()));
    ApiResponse<Map<String, Object>> patched =
        controller.patchSettings(
            owner, id, new PharmacyInventoryController.SettingsRequest(false, true, 10, "A1"));
    assertThat(patched.success()).isTrue();
    controller.patchSettings(owner, id, null);

    when(service.patchDetails(
            any(), eq(id), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any()))
        .thenReturn(Map.of("id", id.toString()));
    controller.patchDetails(
        owner,
        id,
        new PharmacyInventoryController.DetailsRequest(
            "n",
            null,
            null,
            1,
            "t",
            null,
            "TABLET",
            "OTC",
            "30049099",
            BigDecimal.TEN,
            List.of(),
            null));
    controller.patchDetails(owner, id, null);
    verify(service)
        .patchDetails(
            eq(owner), eq(id), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), isNull(), isNull(), isNull());
  }

  @Test
  void patchRackDelegates() {
    UUID id = UUID.randomUUID();
    when(rackLocationService.patchProductRack(any(), eq(id), any(), any()))
        .thenReturn(Map.of("product_id", id.toString(), "rack_locations", List.of("A1-01")));
    assertThat(
            controller
                .patchRack(
                    owner, id, new PharmacyInventoryController.RackPatchRequest("A1-01", "ADD"))
                .success())
        .isTrue();
    controller.patchRack(owner, id, null);
  }
}
