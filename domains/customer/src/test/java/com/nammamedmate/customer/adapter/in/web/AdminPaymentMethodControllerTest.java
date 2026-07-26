package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.PaymentMethodService;
import com.nammamedmate.kernel.api.ApiResponse;
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
class AdminPaymentMethodControllerTest {

  @Mock private PaymentMethodService service;

  private AdminPaymentMethodController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "a");

  @BeforeEach
  void setUp() {
    controller = new AdminPaymentMethodController(service);
  }

  @Test
  void list_delegates() {
    UUID customerId = UUID.randomUUID();
    when(service.listForAdmin(admin, customerId)).thenReturn(Map.of("upi", java.util.List.of()));

    ApiResponse<Map<String, Object>> response = controller.list(admin, customerId);

    assertThat(response.data()).containsKey("upi");
    verify(service).listForAdmin(admin, customerId);
  }
}
