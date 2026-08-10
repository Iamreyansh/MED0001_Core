package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.adapter.in.web.PharmacyPrescriptionController.ApproveRequest;
import com.nammamedmate.prescription.adapter.in.web.PharmacyPrescriptionController.ApprovedMedicineBody;
import com.nammamedmate.prescription.adapter.in.web.PharmacyPrescriptionController.RejectRequest;
import com.nammamedmate.prescription.application.PharmacyRxQueueService;
import com.nammamedmate.prescription.application.PharmacyRxQueueService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyPrescriptionControllerTest {

  private final PharmacyRxQueueService service = mock(PharmacyRxQueueService.class);
  private final PharmacyPrescriptionController controller =
      new PharmacyPrescriptionController(service);
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @Test
  void allEndpointsDelegate() {
    when(service.list(eq(owner), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ListResult(Map.of("prescriptions", List.of()), PaginationMeta.of(1, 20, 0)));
    ApiResponse<Map<String, Object>> list = controller.list(owner, null, null, null, 1, 20, null);
    assertThat(list.success()).isTrue();

    UUID rx = UUID.randomUUID();
    when(service.get(owner, rx)).thenReturn(Map.of("rx_id", rx));
    assertThat(controller.get(owner, rx).data().get("rx_id")).isEqualTo(rx);

    when(service.approve(eq(owner), eq(rx), any(), any())).thenReturn(Map.of("status", "APPROVED"));
    assertThat(
            controller
                .approve(
                    owner,
                    rx,
                    new ApproveRequest(
                        List.of(new ApprovedMedicineBody("Metformin", 60, new BigDecimal("85"))),
                        "n"))
                .data()
                .get("status"))
        .isEqualTo("APPROVED");

    controller.approve(owner, rx, null);
    verify(service).approve(eq(owner), eq(rx), eq(List.of()), eq(null));

    when(service.reject(eq(owner), eq(rx), any(), any())).thenReturn(Map.of("status", "REJECTED"));
    assertThat(
            controller
                .reject(owner, rx, new RejectRequest("ILLEGIBLE", "blurry"))
                .data()
                .get("status"))
        .isEqualTo("REJECTED");
    controller.reject(owner, rx, null);

    when(service.dispense(owner, rx)).thenReturn(Map.of("status", "DISPENSED"));
    assertThat(controller.dispense(owner, rx).data().get("status")).isEqualTo("DISPENSED");

    when(service.dispenseToBilling(owner, rx)).thenReturn(Map.of("medicines_loaded", 1));
    assertThat(controller.dispenseToBilling(owner, rx).data().get("medicines_loaded")).isEqualTo(1);

    ApproveRequest emptyBodies =
        new ApproveRequest(
            java.util.Arrays.asList(null, new ApprovedMedicineBody(null, null, null)), null);
    assertThat(emptyBodies.toMedicines()).hasSize(1);
    assertThat(new ApproveRequest(null, null).toMedicines()).isEmpty();
  }
}
