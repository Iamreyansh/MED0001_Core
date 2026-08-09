package com.nammamedmate.integration.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.GovernmentApiService;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.kernel.error.AppException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GovernmentControllersTest {

  private GovernmentApiService service;
  private GovernmentIntegrationController controller;

  @BeforeEach
  void setUp() {
    service = mock(GovernmentApiService.class);
    controller = new GovernmentIntegrationController(service, new InternalServiceAuth("tok"));
  }

  @Test
  void requiresTokenExceptDigiLockerCallback() {
    assertThatThrownBy(() -> controller.verifyGstin(null, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.digiLockerInitiate(null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.verifyDrugLicence(null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.verifyFssai(null, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> controller.getDrugVerification(null, UUID.randomUUID()))
        .isInstanceOf(AppException.class);

    when(service.digiLockerCallback(any(), any())).thenReturn(Map.of("ok", true));
    assertThat(controller.digiLockerCallback(null).success()).isTrue();
  }

  @Test
  void gstnDigiFssaiHappy() {
    when(service.verifyGstin(any(), any(), any())).thenReturn(Map.of("valid", true));
    when(service.initiateDigiLocker(any(), any(), any(), any()))
        .thenReturn(Map.of("auth_url", "https://x"));
    when(service.verifyFssai(any(), any(), any())).thenReturn(Map.of("valid", true));

    assertThat(
            controller
                .verifyGstin(
                    "tok", new GovernmentIntegrationController.GstnVerifyRequest("g", null, null))
                .data()
                .get("valid"))
        .isEqualTo(true);
    controller.verifyGstin("tok", null);
    assertThat(
            controller
                .digiLockerInitiate(
                    "tok",
                    new GovernmentIntegrationController.DigiLockerInitiateRequest(
                        "p", "PHARMACY_KYC", UUID.randomUUID(), "https://cb"))
                .data()
                .get("auth_url"))
        .isEqualTo("https://x");
    controller.digiLockerInitiate("tok", null);
    controller.verifyFssai(
        "tok", new GovernmentIntegrationController.FssaiVerifyRequest("1", null, null));
    controller.verifyFssai("tok", null);
  }

  @Test
  void drugVerifyReturns202WhenPending() {
    when(service.verifyDrugLicence(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "PENDING", "verification_id", UUID.randomUUID().toString()));
    var accepted = controller.verifyDrugLicence("tok", null);
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    when(service.verifyDrugLicence(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "PENDING"));
    assertThat(controller.verifyDrugLicence("tok", null).getStatusCode()).isEqualTo(HttpStatus.OK);

    when(service.verifyDrugLicence(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "ACTIVE", "valid", true));
    var ok =
        controller.verifyDrugLicence(
            "tok",
            new GovernmentIntegrationController.DrugLicenceRequest(
                "KA/1", "Karnataka", "RETAIL", null, null));
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID id = UUID.randomUUID();
    when(service.getDrugVerification(eq(id))).thenReturn(Map.of("status", "ACTIVE"));
    assertThat(controller.getDrugVerification("tok", id).data().get("status")).isEqualTo("ACTIVE");
    verify(service).getDrugVerification(id);
  }

  @Test
  void callbackUsesBody() {
    when(service.digiLockerCallback(anyString(), anyString()))
        .thenReturn(Map.of("aadhaar_verified", true));
    assertThat(
            controller
                .digiLockerCallback(
                    new GovernmentIntegrationController.DigiLockerCallbackRequest("c", "s"))
                .data()
                .get("aadhaar_verified"))
        .isEqualTo(true);
  }
}
