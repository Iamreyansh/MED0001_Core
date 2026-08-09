package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.RefundFacadeService;
import com.nammamedmate.payment.application.RefundFacadeService.PagedResult;
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

@ExtendWith(MockitoExtension.class)
class FinanceRefundControllerTest {

  @Mock private RefundFacadeService refunds;
  @InjectMocks private AdminFinanceRefundController admin;
  @InjectMocks private CustomerRefundController customerCtrl;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void adminListDetailProcess() {
    when(refunds.listAdmin(eq(finance), eq("PENDING"), isNull(), isNull(), eq(1), eq(20)))
        .thenReturn(
            new PagedResult(Map.of("refunds", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    ApiResponse<Map<String, Object>> list = admin.list(finance, "PENDING", null, null, 1, 20);
    assertThat(list.success()).isTrue();

    UUID id = UUID.randomUUID();
    when(refunds.getAdminDetail(finance, id)).thenReturn(Map.of("refund_id", id.toString()));
    assertThat(admin.detail(finance, id).data()).containsEntry("refund_id", id.toString());

    when(refunds.process(eq(finance), eq(id), eq("ok"))).thenReturn(Map.of("status", "PROCESSING"));
    assertThat(
            admin
                .process(finance, id, new AdminFinanceRefundController.ProcessRequest("ok"))
                .data())
        .containsEntry("status", "PROCESSING");
    assertThat(admin.process(finance, id, null).success()).isTrue();
    verify(refunds).process(eq(finance), eq(id), isNull());
  }

  @Test
  void customerList() {
    when(refunds.listCustomer(eq(customer), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("refunds", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(customerCtrl.list(customer, 1, 20).success()).isTrue();
  }
}
