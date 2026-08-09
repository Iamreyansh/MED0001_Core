package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.CommunicationService;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminCommunicationsControllerTest {

  private CommunicationService service;
  private AdminCommunicationsController controller;
  private MedmatePrincipal admin;

  @BeforeEach
  void setUp() {
    service = mock(CommunicationService.class);
    controller = new AdminCommunicationsController(service);
    admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void anonymousRejectedOnAllEndpoints() {
    assertThatThrownBy(() -> controller.status(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.test(null, Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.usage(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> controller.patchConfig(null, "SMS", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void delegatesHappyPathsIncludingNullBodies() {
    when(service.status(admin)).thenReturn(Map.of("overall_status", "HEALTHY"));
    when(service.testSend(any(), any(), any(), any())).thenReturn(Map.of("status", "SENT"));
    when(service.usage(any(), any())).thenReturn(Map.of("usage", java.util.List.of()));
    when(service.patchConfig(any(), any(), any())).thenReturn(Map.of("channel", "SMS"));

    assertThat(controller.status(admin).data().get("overall_status")).isEqualTo("HEALTHY");
    assertThat(controller.test(admin, null).data().get("status")).isEqualTo("SENT");
    verify(service).testSend(eq(admin), isNull(), isNull(), isNull());
    assertThat(controller.test(admin, Map.of("channel", "SMS", "recipient", "+91")).data())
        .isNotNull();
    assertThat(controller.usage(admin, "SMS").data()).containsKey("usage");
    assertThat(controller.patchConfig(admin, "SMS", null).data().get("channel")).isEqualTo("SMS");
    assertThat(controller.patchConfig(admin, "SMS", Map.of("is_enabled", true)).data()).isNotNull();
  }
}
