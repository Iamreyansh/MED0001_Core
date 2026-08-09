package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.EinvoiceService;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.kernel.error.AppException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EinvoiceControllersTest {

  private EinvoiceService service;
  private EinvoiceIntegrationController controller;

  @BeforeEach
  void setUp() {
    service = mock(EinvoiceService.class);
    controller = new EinvoiceIntegrationController(service, new InternalServiceAuth("tok"));
  }

  @Test
  void requiresToken() {
    assertThatThrownBy(() -> controller.generateIrn(null, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.cancelIrn(null, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.status(null, "irn")).isInstanceOf(AppException.class);
  }

  @Test
  void generateCancelStatusHappy() {
    when(service.generateIrn(any(), any(), any())).thenReturn(Map.of("irn", "abc"));
    when(service.cancelIrn(any(), any(), any())).thenReturn(Map.of("status", "CANCELLED"));
    when(service.status(eq("abc"))).thenReturn(Map.of("status", "ACTIVE"));

    assertThat(
            controller
                .generateIrn(
                    "tok",
                    new EinvoiceIntegrationController.GenerateIrnRequest(
                        UUID.randomUUID(), UUID.randomUUID(), Map.of("invoice_number", "1")))
                .data()
                .get("irn"))
        .isEqualTo("abc");
    controller.generateIrn("tok", null);
    assertThat(
            controller
                .cancelIrn(
                    "tok", new EinvoiceIntegrationController.CancelIrnRequest("abc", "1", "dup"))
                .data()
                .get("status"))
        .isEqualTo("CANCELLED");
    controller.cancelIrn("tok", null);
    assertThat(controller.status("tok", "abc").data().get("status")).isEqualTo("ACTIVE");
    verify(service).status("abc");
  }
}
