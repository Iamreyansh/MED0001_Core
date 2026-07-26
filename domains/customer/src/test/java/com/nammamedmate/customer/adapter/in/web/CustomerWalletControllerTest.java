package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.TxPage;
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
class CustomerWalletControllerTest {

  @Mock private WalletService service;
  private CustomerWalletController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerWalletController(service);
  }

  @Test
  void get_delegates() {
    when(service.getMyWallet(customer)).thenReturn(Map.of("balance", "0"));
    assertThat(controller.get(customer).data()).containsEntry("balance", "0");
  }

  @Test
  void transactions_delegates() {
    when(service.listMyTransactions(eq(customer), eq(1), eq(20), isNull(), isNull(), eq("CREDIT")))
        .thenReturn(new TxPage(List.of(Map.of("type", "CREDIT")), PaginationMeta.of(1, 20, 1)));

    ApiResponse<List<Map<String, Object>>> response =
        controller.transactions(customer, 1, 20, null, null, "CREDIT");

    assertThat(response.data()).hasSize(1);
    assertThat(response.meta().total()).isEqualTo(1);
  }
}
