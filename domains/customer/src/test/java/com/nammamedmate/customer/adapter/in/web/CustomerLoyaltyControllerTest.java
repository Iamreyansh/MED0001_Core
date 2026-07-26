package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.customer.application.LoyaltyService.TxPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerLoyaltyControllerTest {

  @Mock private LoyaltyService service;
  private CustomerLoyaltyController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerLoyaltyController(service);
  }

  @Test
  void get_delegates() {
    when(service.getMyStatus(customer)).thenReturn(Map.of("tier", "GOLD"));
    ApiResponse<Map<String, Object>> response = controller.get(customer);
    assertThat(response.data()).containsEntry("tier", "GOLD");
  }

  @Test
  void transactions_delegates() {
    TxPage page = new TxPage(List.of(Map.of("type", "EARN")), PaginationMeta.of(1, 20, 1));
    when(service.listMyTransactions(eq(customer), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(page);
    ApiResponse<List<Map<String, Object>>> response =
        controller.transactions(customer, null, null, null, null);
    assertThat(response.data()).hasSize(1);
    assertThat(response.meta()).isEqualTo(page.meta());
  }
}
