package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.pos.application.KhataService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyKhataControllerTest {

  @Mock KhataService khataService;
  @InjectMocks PharmacyKhataController controller;

  MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @Test
  void listAndDetailAndMutations() {
    when(khataService.list(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new KhataService.ListResult(
                Map.of("customers", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    Object list = controller.list(principal, false, null, null, 1, 20, null);
    assertThat(list).isInstanceOf(Map.class);

    when(khataService.detail(any(), any(), any())).thenReturn(Map.of("total_outstanding", 0));
    ApiResponse<Map<String, Object>> detail = controller.detail(principal, UUID.randomUUID(), null);
    assertThat(detail.success()).isTrue();

    when(khataService.repay(any(), any(), any())).thenReturn(Map.of("receipt_number", "RCPT-1"));
    ResponseEntity<ApiResponse<Map<String, Object>>> repay =
        controller.repay(principal, UUID.randomUUID(), Map.of());
    assertThat(repay.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    when(khataService.remind(any(), any(), any())).thenReturn(Map.of("channel", "SMS"));
    assertThat(controller.remind(principal, UUID.randomUUID(), Map.of()).success()).isTrue();
  }

  @Test
  void paymentHistoryJsonAndExport() {
    when(khataService.paymentHistory(
            any(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull(), isNull()))
        .thenReturn(
            new KhataService.ListResult(
                Map.of("repayments", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    Object json = controller.paymentHistory(principal, null, null, null, null, 1, 20, null, null);
    assertThat(json).isInstanceOf(Map.class);

    when(khataService.paymentHistory(
            any(), isNull(), isNull(), isNull(), isNull(), any(), any(), eq("EXCEL"), isNull()))
        .thenReturn(
            new KhataService.FileExport(
                "khata.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[] {'P', 'K', 3, 4}));
    Object file =
        controller.paymentHistory(principal, null, null, null, null, 1, 20, "EXCEL", null);
    assertThat(file).isInstanceOf(ResponseEntity.class);
  }
}
