package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.PharmacyReorderService;
import com.nammamedmate.inventory.application.PharmacyReorderService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
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
class PharmacyReorderControllerTest {

  @Mock private PharmacyReorderService service;
  private PharmacyReorderController controller;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    controller = new PharmacyReorderController(service);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  }

  @Test
  void delegatesAllEndpoints() {
    ListPage page = new ListPage(Map.of("kpi", Map.of()), PaginationMeta.of(1, 50, 0));
    when(service.listSuggestions(eq(owner), isNull(), isNull(), isNull())).thenReturn(page);
    when(service.listPurchaseOrders(eq(owner), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(page);
    when(service.createPo(any(), any(), any())).thenReturn(Map.of("po_id", "x"));
    when(service.patchPo(any(), any(), any(), any(), any())).thenReturn(Map.of("po_id", "x"));
    when(service.sendPo(any(), any(), any(), any())).thenReturn(Map.of("status", "SENT"));
    when(service.recordGrn(any(), any(), any(), any())).thenReturn(Map.of("grn_id", "g"));
    when(service.refresh(any())).thenReturn(Map.of("items_below_reorder_level", 1));

    assertThat(controller.list(owner, null, null, null).get("success")).isEqualTo(true);
    assertThat(controller.listPurchaseOrders(owner, null, null, null, null).get("success"))
        .isEqualTo(true);

    ResponseEntity<ApiResponse<Map<String, Object>>> created = controller.createPo(owner, null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    UUID poId = UUID.randomUUID();
    PharmacyReorderController.CreatePoRequest createReq =
        new PharmacyReorderController.CreatePoRequest(
            UUID.randomUUID(),
            List.of(Map.of("product_id", UUID.randomUUID().toString(), "quantity", 1)));
    assertThat(controller.createPo(owner, createReq).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(controller.patchPo(owner, poId, null).data()).containsKey("po_id");
    assertThat(
            controller
                .patchPo(
                    owner,
                    poId,
                    new PharmacyReorderController.PatchPoRequest(List.of(), List.of(), List.of()))
                .data())
        .containsKey("po_id");

    assertThat(controller.sendPo(owner, poId, null).data().get("status")).isEqualTo("SENT");
    assertThat(
            controller
                .sendPo(owner, poId, new PharmacyReorderController.SendPoRequest("WHATSAPP", null))
                .data()
                .get("status"))
        .isEqualTo("SENT");

    assertThat(controller.recordGrn(owner, poId, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(
            controller
                .recordGrn(
                    owner,
                    poId,
                    new PharmacyReorderController.RecordGrnRequest("INV", LocalDate.of(2026, 8, 9)))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    assertThat(controller.refresh(owner).data().get("items_below_reorder_level")).isEqualTo(1);
  }
}
