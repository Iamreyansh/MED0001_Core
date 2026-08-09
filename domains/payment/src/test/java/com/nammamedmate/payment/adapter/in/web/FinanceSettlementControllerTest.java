package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.SettlementFacadeService;
import com.nammamedmate.payment.application.SettlementFacadeService.PagedResult;
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
class FinanceSettlementControllerTest {

  @Mock private SettlementFacadeService settlements;
  @InjectMocks private AdminFinanceSettlementController admin;
  @InjectMocks private PharmacyFinanceSettlementController pharmacy;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @Test
  void adminListDetailReleaseHoldReleaseAll() {
    when(settlements.listAdmin(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(
                Map.of("settlements", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(settlements.getAdminDetail(any(), any())).thenReturn(Map.of("status", "PENDING"));
    when(settlements.release(any(), any(), any(), any())).thenReturn(Map.of("status", "RELEASED"));
    when(settlements.hold(any(), any(), any(), any())).thenReturn(Map.of("status", "HELD"));
    when(settlements.releaseAll(any(), any(), any(), any())).thenReturn(Map.of("released", 1));

    UUID id = UUID.randomUUID();
    ApiResponse<Map<String, Object>> list = admin.list(finance, null, null, null, 1, 20);
    assertThat(list.success()).isTrue();
    assertThat(admin.detail(finance, id).data()).containsEntry("status", "PENDING");
    assertThat(
            admin
                .release(
                    finance, id, "key", new AdminFinanceSettlementController.ReleaseRequest("n"))
                .data())
        .containsEntry("status", "RELEASED");
    assertThat(
            admin
                .hold(finance, id, new AdminFinanceSettlementController.HoldRequest("r", "n"))
                .data())
        .containsEntry("status", "HELD");
    assertThat(
            admin
                .releaseAll(
                    finance,
                    "bulk",
                    new AdminFinanceSettlementController.ReleaseAllRequest(null, "n"))
                .data())
        .containsEntry("released", 1);

    verify(settlements).release(eq(finance), eq(id), eq("n"), eq("key"));
  }

  @Test
  void pharmacyListAndDetail() {
    when(settlements.listPharmacy(any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(
                Map.of("settlements", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(settlements.getPharmacyDetail(any(), any())).thenReturn(Map.of("status", "RELEASED"));
    UUID id = UUID.randomUUID();
    assertThat(pharmacy.list(owner, null, 1, 20).success()).isTrue();
    assertThat(pharmacy.detail(owner, id).data()).containsEntry("status", "RELEASED");
    verify(settlements).getPharmacyDetail(owner, id);
    verify(settlements).listPharmacy(eq(owner), isNull(), eq(1), eq(20));
  }

  @Test
  void nullBodiesDefault() {
    when(settlements.release(any(), any(), isNull(), any())).thenReturn(Map.of("ok", true));
    when(settlements.hold(any(), any(), isNull(), isNull())).thenReturn(Map.of("ok", true));
    when(settlements.releaseAll(any(), isNull(), isNull(), any())).thenReturn(Map.of("ok", true));
    UUID id = UUID.randomUUID();
    assertThat(admin.release(finance, id, "k", null).data()).containsKey("ok");
    assertThat(admin.hold(finance, id, null).data()).containsKey("ok");
    assertThat(admin.releaseAll(finance, "k", null).data()).containsKey("ok");
  }
}
