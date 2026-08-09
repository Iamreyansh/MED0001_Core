package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.RiderPayoutFacadeService;
import com.nammamedmate.payment.application.RiderPayoutFacadeService.PagedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FinanceRiderPayoutControllerTest {

  @Mock private RiderPayoutFacadeService payouts;
  @InjectMocks private AdminFinanceRiderPayoutController admin;
  @InjectMocks private RiderPayoutHistoryController riderHistory;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");

  @Test
  void adminListLedgerReleaseReleaseAll() {
    when(payouts.listAdmin(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("payouts", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(payouts.ledger(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("entries", java.util.List.of()), PaginationMeta.of(1, 50, 0)));
    when(payouts.release(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "RELEASED"));
    when(payouts.releaseAll(any(), any(), any(), any(), any())).thenReturn(Map.of("released", 1));

    UUID riderId = UUID.randomUUID();
    UUID payoutId = UUID.randomUUID();
    ApiResponse<Map<String, Object>> list = admin.list(finance, null, null, null, 1, 20);
    assertThat(list.success()).isTrue();
    assertThat(admin.ledger(finance, riderId, null, null, 1, 50).data()).containsKey("entries");
    assertThat(
            admin
                .release(
                    finance,
                    riderId,
                    "key",
                    new AdminFinanceRiderPayoutController.ReleaseRequest(payoutId, "n"))
                .data())
        .containsEntry("status", "RELEASED");
    assertThat(
            admin
                .releaseAll(
                    finance,
                    "bulk",
                    new AdminFinanceRiderPayoutController.ReleaseAllRequest(
                        10000.00, LocalDate.of(2026, 7, 14), "n"))
                .data())
        .containsEntry("released", 1);

    verify(payouts).release(eq(finance), eq(riderId), eq(payoutId), eq("n"), eq("key"));
  }

  @Test
  void nullBodiesDefault() {
    when(payouts.release(any(), any(), isNull(), isNull(), any())).thenReturn(Map.of("ok", true));
    when(payouts.releaseAll(any(), isNull(), isNull(), isNull(), any()))
        .thenReturn(Map.of("ok", true));
    UUID riderId = UUID.randomUUID();
    assertThat(admin.release(finance, riderId, "k", null).data()).containsKey("ok");
    assertThat(admin.releaseAll(finance, "k", null).data()).containsKey("ok");
  }

  @Test
  void riderHistory() {
    when(payouts.history(any(), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("payouts", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(riderHistory.history(rider, 1, 20).success()).isTrue();
    verify(payouts).history(eq(rider), eq(1), eq(20));
  }
}
