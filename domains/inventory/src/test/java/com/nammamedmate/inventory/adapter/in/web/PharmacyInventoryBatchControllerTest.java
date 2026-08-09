package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.InventoryBatchService;
import com.nammamedmate.inventory.application.InventoryBatchService.FileExport;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyInventoryBatchControllerTest {

  @Mock private InventoryBatchService service;
  private PharmacyInventoryBatchController controller;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyInventoryBatchController(service);
  }

  @Test
  void expiryAlertsAndReportJson() {
    when(service.expiryAlerts(owner)).thenReturn(Map.of("summary", Map.of()));
    assertThat(controller.expiryAlerts(owner).success()).isTrue();

    when(service.expiryReport(owner, 4, null)).thenReturn(Map.of("total_batches", 1));
    Object json = controller.expiryReport(owner, 4, null);
    assertThat(json).isInstanceOf(ApiResponse.class);
  }

  @Test
  void expiryReportFile() {
    when(service.expiryReport(owner, 4, "PDF"))
        .thenReturn(new FileExport(new byte[] {'%', 'P'}, "expiry-report.pdf", "application/pdf"));
    Object body = controller.expiryReport(owner, 4, "PDF");
    assertThat(body).isInstanceOf(ResponseEntity.class);
  }

  @Test
  void batchCrud() {
    UUID product = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    when(service.listBatches(owner, product, false))
        .thenReturn(Map.of("batches", java.util.List.of()));
    assertThat(controller.listBatches(owner, product, false).success()).isTrue();

    when(service.addBatch(any(), eq(product), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("id", batch.toString(), "topped_up", false));
    assertThat(
            controller
                .addBatch(
                    owner,
                    product,
                    new PharmacyInventoryBatchController.AddBatchRequest(
                        "BN",
                        LocalDate.of(2027, 1, 1),
                        null,
                        10,
                        0,
                        BigDecimal.TEN,
                        BigDecimal.valueOf(20)))
                .getStatusCode()
                .value())
        .isEqualTo(201);
    controller.addBatch(owner, product, null);

    when(service.adjustBatch(owner, product, batch, -5, "DAMAGE"))
        .thenReturn(Map.of("after_qty", 5));
    controller.adjustBatch(
        owner,
        product,
        batch,
        new PharmacyInventoryBatchController.AdjustBatchRequest(-5, "DAMAGE"));
    controller.adjustBatch(owner, product, batch, null);

    when(service.writeOffBatch(owner, product, batch, "EXPIRED", null))
        .thenReturn(Map.of("is_active", false));
    controller.writeOffBatch(
        owner,
        product,
        batch,
        new PharmacyInventoryBatchController.WriteOffRequest("EXPIRED", null));
    controller.writeOffBatch(owner, product, batch, null);
    verify(service).writeOffBatch(owner, product, batch, null, null);
  }
}
