package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.InternalWalletTokenAuth;
import com.nammamedmate.payment.application.WalletFacadeService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

  @Mock private WalletFacadeService wallets;
  private WalletController controller;
  private final UUID customerId = UUID.randomUUID();
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
  private static final String TOKEN = "local-internal-wallet-token";

  @BeforeEach
  void setUp() {
    controller = new WalletController(wallets, new InternalWalletTokenAuth(TOKEN));
  }

  @Test
  void debit_requiresToken() {
    assertThatThrownBy(
            () ->
                controller.debit(
                    null,
                    new WalletController.DebitRequest(customerId, 50, UUID.randomUUID(), "idem")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void debit_delegates() {
    when(wallets.debit(eq(customerId), eq(50), any(), eq("idem")))
        .thenReturn(Map.of("already_processed", false));
    ApiResponse<Map<String, Object>> res =
        controller.debit(
            TOKEN, new WalletController.DebitRequest(customerId, 50, UUID.randomUUID(), "idem"));
    assertThat(res.data()).containsEntry("already_processed", false);
  }

  @Test
  void debit_nullBody() {
    when(wallets.debit(isNull(), isNull(), isNull(), isNull())).thenReturn(Map.of());
    assertThat(controller.debit(TOKEN, null).data()).isEmpty();
  }

  @Test
  void credit_adminSkipsToken() {
    when(wallets.credit(eq(admin), eq(customerId), eq(100), eq("REFUND"), eq("ref"), eq("note")))
        .thenReturn(Map.of("reason", "REFUND"));
    ApiResponse<Map<String, Object>> res =
        controller.credit(
            admin,
            null,
            new WalletController.CreditRequest(customerId, 100, "REFUND", "ref", "note"));
    assertThat(res.data()).containsEntry("reason", "REFUND");
    verify(wallets).credit(admin, customerId, 100, "REFUND", "ref", "note");
  }

  @Test
  void credit_opsRequiresToken() {
    assertThatThrownBy(
            () ->
                controller.credit(
                    ops,
                    null,
                    new WalletController.CreditRequest(customerId, 100, "REFUND", "ref", "note")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void credit_systemRequiresToken() {
    when(wallets.credit(isNull(), eq(customerId), eq(100), eq("REFUND"), isNull(), isNull()))
        .thenReturn(Map.of());
    assertThat(
            controller
                .credit(
                    null,
                    TOKEN,
                    new WalletController.CreditRequest(customerId, 100, "REFUND", null, null))
                .data())
        .isEmpty();
  }

  @Test
  void credit_nullBodyWithToken() {
    when(wallets.credit(isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of());
    assertThat(controller.credit(null, TOKEN, null).data()).isEmpty();
  }
}
