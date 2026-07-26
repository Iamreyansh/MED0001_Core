package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.CustomerProfileService;
import com.nammamedmate.customer.application.CustomerProfileService.UpdateProfileCommand;
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
class CustomerProfileControllerTest {

  @Mock private CustomerProfileService service;

  private CustomerProfileController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerProfileController(service);
  }

  @Test
  void getMe_delegatesToService() {
    when(service.getMe(customer)).thenReturn(Map.of("id", customer.subject()));

    ApiResponse<Map<String, Object>> response = controller.getMe(customer);

    assertThat(response.data()).containsEntry("id", customer.subject());
    verify(service).getMe(customer);
  }

  @Test
  void updateMe_delegatesMappedCommand() {
    when(service.updateMe(eq(customer), any(UpdateProfileCommand.class)))
        .thenReturn(Map.of("name", "Ada"));

    ApiResponse<Map<String, Object>> response =
        controller.updateMe(
            customer,
            new CustomerProfileController.UpdateProfileRequest("Ada", null, null, "female", "en"));

    assertThat(response.data()).containsEntry("name", "Ada");
    verify(service).updateMe(customer, new UpdateProfileCommand("Ada", null, null, "female", "en"));
  }

  @Test
  void updateMe_nullBody_delegatesEmptyCommand() {
    when(service.updateMe(eq(customer), any(UpdateProfileCommand.class)))
        .thenReturn(Map.of("id", customer.subject()));

    controller.updateMe(customer, null);

    verify(service).updateMe(customer, new UpdateProfileCommand(null, null, null, null, null));
  }

  @Test
  void deleteMe_delegatesReason() {
    when(service.requestDeletion(customer, "privacy")).thenReturn(Map.of("message", "ok"));

    ApiResponse<Map<String, Object>> response =
        controller.deleteMe(
            customer, new CustomerProfileController.DeleteAccountRequest("privacy"));

    assertThat(response.data()).containsEntry("message", "ok");
    verify(service).requestDeletion(customer, "privacy");
  }

  @Test
  void deleteMe_nullBody_delegatesNullReason() {
    when(service.requestDeletion(customer, null)).thenReturn(Map.of("message", "ok"));

    controller.deleteMe(customer, null);

    verify(service).requestDeletion(customer, null);
  }

  @Test
  void cancelDeletion_delegatesToService() {
    when(service.cancelDeletion(customer)).thenReturn(Map.of("message", "cancelled"));

    ApiResponse<Map<String, Object>> response = controller.cancelDeletion(customer);

    assertThat(response.data()).containsEntry("message", "cancelled");
    verify(service).cancelDeletion(customer);
  }
}
