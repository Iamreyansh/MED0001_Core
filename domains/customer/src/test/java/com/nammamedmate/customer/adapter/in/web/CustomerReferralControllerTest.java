package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.adapter.in.web.CustomerReferralController.ApplyRequest;
import com.nammamedmate.customer.application.ReferralService;
import com.nammamedmate.customer.application.ReferralService.ApplyCommand;
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
class CustomerReferralControllerTest {

  @Mock private ReferralService service;
  private CustomerReferralController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerReferralController(service);
  }

  @Test
  void get_delegates() {
    when(service.getMyReferral(customer)).thenReturn(Map.of("referral_code", "MEDRAM7"));
    assertThat(controller.get(customer).data()).containsEntry("referral_code", "MEDRAM7");
  }

  @Test
  void apply_delegatesWithCode() {
    when(service.applyCode(eq(customer), any(ApplyCommand.class)))
        .thenReturn(Map.of("status", "PENDING"));
    ApiResponse<Map<String, Object>> response =
        controller.apply(customer, new ApplyRequest("MEDRAM7"));
    assertThat(response.data()).containsEntry("status", "PENDING");
    ArgumentCaptor<ApplyCommand> captor = ArgumentCaptor.forClass(ApplyCommand.class);
    org.mockito.Mockito.verify(service).applyCode(eq(customer), captor.capture());
    assertThat(captor.getValue().referrerCode()).isEqualTo("MEDRAM7");
  }

  @Test
  void apply_nullBody_passesNullCode() {
    when(service.applyCode(eq(customer), any(ApplyCommand.class)))
        .thenReturn(Map.of("status", "PENDING"));
    controller.apply(customer, null);
    ArgumentCaptor<ApplyCommand> captor = ArgumentCaptor.forClass(ApplyCommand.class);
    org.mockito.Mockito.verify(service).applyCode(eq(customer), captor.capture());
    assertThat(captor.getValue().referrerCode()).isNull();
  }
}
