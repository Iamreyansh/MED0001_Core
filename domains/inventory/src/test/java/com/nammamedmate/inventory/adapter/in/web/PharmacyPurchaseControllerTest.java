package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.PurchaseGrnService;
import com.nammamedmate.inventory.application.PurchaseGrnService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class PharmacyPurchaseControllerTest {

  @Mock private PurchaseGrnService service;
  private PharmacyPurchaseController controller;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyPurchaseController(service);
  }

  @Test
  void allEndpointsDelegate() {
    UUID grnId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID dist = UUID.randomUUID();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ok", true);

    when(service.list(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListPage(Map.of("grns", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    Map<String, Object> listed = controller.list(owner, "DRAFT", dist, null, null, null, 1, 20);
    assertThat(listed.get("success")).isEqualTo(true);

    when(service.create(any(), any(), any(), any())).thenReturn(data);
    ResponseEntity<ApiResponse<Map<String, Object>>> created =
        controller.create(
            owner,
            new PharmacyPurchaseController.CreateGrnRequest(dist, "INV", LocalDate.of(2026, 7, 1)));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    when(service.addItem(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any()))
        .thenReturn(data);
    assertThat(
            controller
                .addItem(
                    owner,
                    grnId,
                    new PharmacyPurchaseController.AddItemRequest(
                        null,
                        UUID.randomUUID(),
                        false,
                        null,
                        null,
                        null,
                        null,
                        "BN",
                        LocalDate.of(2027, 1, 1),
                        null,
                        1,
                        0,
                        new BigDecimal("1"),
                        new BigDecimal("2"),
                        new BigDecimal("12")))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(service.patchItem(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(data);
    assertThat(controller.patchItem(owner, grnId, itemId, null).data()).isEqualTo(data);

    when(service.deleteItem(any(), any(), any())).thenReturn(data);
    assertThat(controller.deleteItem(owner, grnId, itemId).data()).isEqualTo(data);

    when(service.saveAndStock(any(), any())).thenReturn(data);
    assertThat(controller.saveAndStock(owner, grnId).data()).isEqualTo(data);

    when(service.importCsv(any(), any(), any(), any(), any())).thenReturn(data);
    MockMultipartFile file = new MockMultipartFile("csv_file", "a.csv", "text/csv", "x".getBytes());
    assertThat(controller.importCsv(owner, file, dist, "INV", LocalDate.of(2026, 7, 1)).data())
        .isEqualTo(data);

    when(service.confirmImport(any(), any())).thenReturn(data);
    assertThat(controller.confirmImport(owner, grnId).data()).isEqualTo(data);

    when(service.get(any(), any())).thenReturn(data);
    assertThat(controller.get(owner, grnId).data()).isEqualTo(data);

    controller.create(owner, null);
    verify(service).create(eq(owner), isNull(), isNull(), isNull());

    controller.addItem(owner, grnId, null);
    controller.patchItem(
        owner,
        grnId,
        itemId,
        new PharmacyPurchaseController.PatchItemRequest(1, null, null, null, null, null));
  }
}
