package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.AdminCreditCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminWalletControllerTest {

  @Mock private WalletService service;
  private AdminWalletController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminWalletController(service);
  }

  @Test
  void credit_mapsBodyAndIdempotencyHeader() {
    UUID customerId = UUID.randomUUID();
    when(service.adminCredit(eq(admin), eq(customerId), any(AdminCreditCommand.class)))
        .thenReturn(Map.of("amount_credited", 100));

    ApiResponse<Map<String, Object>> response =
        controller.credit(
            admin,
            customerId,
            "idem-abc",
            new AdminWalletController.CreditRequest(100, "GOODWILL", "note", "ref"));

    assertThat(response.data()).containsEntry("amount_credited", 100);
    ArgumentCaptor<AdminCreditCommand> captor = ArgumentCaptor.forClass(AdminCreditCommand.class);
    verify(service).adminCredit(eq(admin), eq(customerId), captor.capture());
    assertThat(captor.getValue().reason()).isEqualTo("GOODWILL");
    assertThat(captor.getValue().referenceId()).isEqualTo("ref");
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("idem-abc");
  }

  @Test
  void credit_nullBody() {
    UUID customerId = UUID.randomUUID();
    when(service.adminCredit(eq(admin), eq(customerId), isNull())).thenReturn(Map.of());
    controller.credit(admin, customerId, null, null);
    verify(service).adminCredit(admin, customerId, null);
  }
}
