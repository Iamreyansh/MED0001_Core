package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyProfileController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyProfileController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyPublicLogoController;
import com.nammamedmate.pharmacy.adapter.out.notify.LoggingProfileContactNotifier;
import com.nammamedmate.pharmacy.application.AdminPharmacyProfileService;
import com.nammamedmate.pharmacy.application.PharmacyLogoService;
import com.nammamedmate.pharmacy.application.PharmacyProfileService;
import com.nammamedmate.pharmacy.application.port.out.ProfileContactNotifier;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PharmacyProfileAdapterCoverageTest {

  @Test
  void pharmacyProfileControllerEndpoints() {
    PharmacyProfileService service = mock(PharmacyProfileService.class);
    when(service.getProfile(any())).thenReturn(Map.of("pharmacy_id", "x"));
    when(service.patchProfile(any(), any()))
        .thenReturn(Map.of("updated_fields", java.util.List.of()));
    when(service.patchTax(any(), any())).thenReturn(Map.of("re_verification_triggered", false));
    when(service.getCompleteness(any())).thenReturn(Map.of("completeness_pct", 50));
    when(service.saveBankAccount(any(), any()))
        .thenReturn(Map.of("verification_status", "PENDING"));
    when(service.getBankAccount(any())).thenReturn(Map.of("bank_account_id", "b"));
    when(service.verifyContact(any(), any(), any())).thenReturn(Map.of("verified", true));

    PharmacyLogoService logos = mock(PharmacyLogoService.class);
    when(logos.uploadLogo(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              java.util.function.Function<String, String> urls = inv.getArgument(4);
              urls.apply("shop.png");
              return Map.of("logo_url", "https://api.example/shop.png");
            });

    PharmacyProfileController controller = new PharmacyProfileController(service, logos);
    MedmatePrincipal principal = mock(MedmatePrincipal.class);
    when(principal.pharmacyId()).thenReturn(UUID.randomUUID());

    assertThat(controller.getProfile(principal).success()).isTrue();
    assertThat(controller.patchProfile(principal, Map.of()).success()).isTrue();
    assertThat(controller.patchTax(principal, Map.of()).success()).isTrue();
    assertThat(controller.completeness(principal).success()).isTrue();
    assertThat(controller.saveBankAccount(principal, Map.of()).getBody().success()).isTrue();
    assertThat(controller.getBankAccount(principal).success()).isTrue();
    assertThat(controller.saveBankAccount(principal, null).getBody().success()).isTrue();
    assertThat(
            controller
                .verifyContact(
                    principal,
                    new PharmacyProfileController.VerifyContactRequest("PHONE", "123456"))
                .success())
        .isTrue();

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("https");
    request.setServerName("api.example");
    request.setServerPort(443);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      MockMultipartFile file =
          new MockMultipartFile("file", "shop.png", "image/png", new byte[] {1, 2, 3});
      assertThat(controller.uploadLogo(principal, file).getStatusCode().value()).isEqualTo(201);
      assertThat(controller.uploadLogo(principal, null).getBody().success()).isTrue();
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    PharmacyPublicLogoController publicLogos = new PharmacyPublicLogoController(logos);
    assertThat(publicLogos.getLogo("../secret.png").getStatusCode().value()).isEqualTo(404);
    UUID id = UUID.randomUUID();
    when(logos.readPublicLogo(any(), any())).thenReturn(null);
    assertThat(publicLogos.getLogo(id + ".png").getStatusCode().value()).isEqualTo(404);
    when(logos.readPublicLogo(any(), any()))
        .thenReturn(new PharmacyLogoService.PublicLogo(new byte[] {9}, "image/png"));
    assertThat(publicLogos.getLogo(id + ".png").getBody()).containsExactly((byte) 9);
  }

  @Test
  void adminPharmacyProfileControllerEndpoints() {
    AdminPharmacyProfileService service = mock(AdminPharmacyProfileService.class);
    when(service.patchProfile(any(), any(), any(), any()))
        .thenReturn(Map.of("changed_fields", java.util.List.of()));
    when(service.getBankAccount(any(), any())).thenReturn(Map.of("bank_account_id", "b"));

    AdminPharmacyProfileController controller = new AdminPharmacyProfileController(service);
    MedmatePrincipal principal = mock(MedmatePrincipal.class);
    UUID id = UUID.randomUUID();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("127.0.0.1");

    ApiResponse<Map<String, Object>> patch = controller.patchProfile(principal, id, Map.of(), req);
    assertThat(patch.success()).isTrue();
    MockHttpServletRequest blankIp = new MockHttpServletRequest();
    blankIp.setRemoteAddr("");
    assertThat(controller.patchProfile(principal, id, Map.of(), blankIp).success()).isTrue();
    MockHttpServletRequest nullIp = new MockHttpServletRequest();
    nullIp.setRemoteAddr(null);
    assertThat(controller.patchProfile(principal, id, Map.of(), nullIp).success()).isTrue();
    MockHttpServletRequest spacedIp = new MockHttpServletRequest();
    spacedIp.setRemoteAddr("  10.0.0.1  ");
    assertThat(controller.patchProfile(principal, id, Map.of(), spacedIp).success()).isTrue();
    assertThat(controller.getBankAccount(principal, id).success()).isTrue();
  }

  @Test
  void loggingProfileContactNotifierDoesNotThrow() {
    LoggingProfileContactNotifier notifier = new LoggingProfileContactNotifier();
    notifier.sendEmailOtp("a@test", "123456");
    notifier.sendSmsOtp("+919876543210", "123456");
    notifier.sendEmailOtp("a@test", null);
    notifier.sendSmsOtp("+919876543210", null);
    ProfileContactNotifier.NOOP.sendEmailOtp("x", "1");
    ProfileContactNotifier.NOOP.sendSmsOtp("x", "1");
  }
}
