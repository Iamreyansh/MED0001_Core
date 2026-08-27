package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyProfileController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyProfileController;
import com.nammamedmate.pharmacy.application.PharmacyProfileBranchCoverageTest.TrackingRegistrationStore;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeAudit;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeChangeRequests;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakePincodes;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileOtps;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileStore;
import com.nammamedmate.pharmacy.domain.LogoUrlValidator;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.pharmacy.domain.OperatingHoursValidator;
import com.nammamedmate.pharmacy.domain.ProfileCompleteness;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyProfileFinalCoverageTest {

  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void staticAuthHelpers() {
    assertThatThrownBy(() -> PharmacyProfileService.requireOwner(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> PharmacyProfileService.requireOwner(noPharmacy))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal staff =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, PID, TokenScope.FULL, "j");
    assertThatThrownBy(() -> PharmacyProfileService.requireOwner(staff))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> PharmacyProfileService.requirePharmacyRole(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void domainAndControllerNullBodies() {
    assertThat(MagicProfileOtp.matches("EMAIL", "a@other.com", "123456")).isFalse();
    assertThat(MagicProfileOtp.matches("BAD", "x", "123456")).isFalse();
    assertThat(MagicProfileOtp.isTestPhone(null)).isFalse();

    LogoUrlValidator.requireValid("https://cdn.example.com/ok.jpeg");

    PharmacyProfileService service = mock(PharmacyProfileService.class);
    when(service.getProfile(any())).thenReturn(Map.of());
    when(service.patchProfile(any(), any())).thenReturn(Map.of());
    when(service.patchTax(any(), any())).thenReturn(Map.of());
    when(service.verifyContact(any(), any(), any())).thenReturn(Map.of());
    PharmacyProfileController controller =
        new PharmacyProfileController(service, mock(PharmacyLogoService.class));
    MedmatePrincipal p = mock(MedmatePrincipal.class);
    assertThat(controller.patchProfile(p, null).success()).isTrue();
    assertThat(controller.patchTax(p, null).success()).isTrue();
    assertThat(controller.verifyContact(p, null).success()).isTrue();

    AdminPharmacyProfileService admin = mock(AdminPharmacyProfileService.class);
    when(admin.patchProfile(any(), any(), any(), any())).thenReturn(Map.of());
    AdminPharmacyProfileController adminController = new AdminPharmacyProfileController(admin);
    org.springframework.mock.web.MockHttpServletRequest req =
        new org.springframework.mock.web.MockHttpServletRequest();
    assertThat(adminController.patchProfile(p, PID, null, req).success()).isTrue();
  }

  @Test
  void operatingHoursClosedDayWeekValid() {
    List<Map<String, Object>> week = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("day_of_week", d);
      e.put("is_closed", d == 6);
      if (d != 6) {
        e.put("open_time", "09:00");
        e.put("close_time", "18:00");
      } else {
        e.put("open_time", null);
        e.put("close_time", null);
      }
      week.add(e);
    }
    OperatingHoursValidator.requireValid(week);
    assertThatThrownBy(
            () ->
                OperatingHoursValidator.requireValid(
                    List.of(
                        Map.of(
                            "day_of_week",
                            -1,
                            "open_time",
                            "09:00",
                            "close_time",
                            "18:00",
                            "is_closed",
                            false))))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(null))
        .isInstanceOf(AppException.class);
  }

  @Test
  void profileCompletenessPartialAddress() {
    var sparse =
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord(
            PID,
            "C",
            "B",
            null,
            null,
            null,
            null,
            null,
            null,
            "PHARMACY",
            Map.of("city", "X"),
            "ACTIVE",
            "FREE",
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            null,
            java.time.Instant.now(),
            java.time.Instant.now());
    ProfileCompleteness.Result r = ProfileCompleteness.calculate(sparse, List.of(), null);
    assertThat(r.completenessPct()).isLessThan(50);
  }

  @Test
  void adminNoOpPatchAndFinanceRead() throws Exception {
    FakeProfileStore profiles = new FakeProfileStore();
    Map<String, Object> address =
        Map.of("pincode", "560034", "flat", "1", "area", "a", "city", "c", "state", "s");
    profiles.record =
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord(
            PID,
            "PHM",
            "Biz",
            "t",
            "https://cdn/x.png",
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            address,
            "ACTIVE",
            "FREE",
            "29AABPP1234F1ZZ",
            "AABPP1234F",
            "DL",
            "11223344556677",
            true,
            false,
            false,
            true,
            false,
            "P",
            java.time.Instant.now(),
            java.time.Instant.now());
    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    PharmacyProfileService profileService =
        new PharmacyProfileService(
            profiles,
            new TrackingRegistrationStore(),
            new FakeChangeRequests(),
            new FakeProfileOtps(),
            new FakePincodes(),
            (a, b, c) ->
                new com.nammamedmate.pharmacy.application.port.out.PennyDropPort.PennyDropResult(
                    "R", "PENDING"),
            new com.nammamedmate.security.AesGcmCipher(new byte[32]),
            new com.nammamedmate.messaging.OutboxPublisher(
                new com.nammamedmate.messaging.InMemoryOutboxStore(),
                new com.fasterxml.jackson.databind.ObjectMapper()),
            rl,
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(),
            java.time.Clock.systemUTC());
    AdminPharmacyProfileService admin =
        new AdminPharmacyProfileService(
            profiles, new FakeAudit(), profileService, rl, java.time.Clock.systemUTC());
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    Map<String, Object> noOp = admin.patchProfile(ops, PID, Map.of(), "ip");
    assertThat(noOp.get("changed_fields")).asList().isEmpty();
    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    profileService.saveBankAccount(
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j"),
        Map.of(
            "account_holder",
            "H",
            "bank_name",
            "B",
            "account_number",
            "123456789",
            "ifsc_code",
            "HDFC0001234",
            "account_type",
            "CURRENT"));
    assertThat(admin.getBankAccount(finance, PID)).containsKey("bank_account_id");
  }
}
