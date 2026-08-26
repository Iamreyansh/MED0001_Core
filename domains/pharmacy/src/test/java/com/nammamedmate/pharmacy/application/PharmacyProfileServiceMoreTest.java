package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeAudit;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeChangeRequests;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakePincodes;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileOtps;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileStore;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort.PennyDropResult;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PharmacyProfileServiceMoreTest {

  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final byte[] AES_KEY = new byte[32];

  private FakeProfileStore profiles;
  private FakePincodes pincodes;
  private PharmacyProfileService service;
  private AdminPharmacyProfileService adminService;
  private FakeAudit audit;

  @BeforeEach
  void setUp() throws Exception {
    profiles = new FakeProfileStore();
    pincodes = new FakePincodes();
    audit = new FakeAudit();
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    AesGcmCipher cipher = new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {1}));
    service =
        new PharmacyProfileService(
            profiles,
            new FakeRegistrationStore(),
            new FakeChangeRequests(),
            new FakeProfileOtps(),
            pincodes,
            (pharmacyId, ifsc, last4) -> new PennyDropResult("RZP-X", "PENDING"),
            cipher,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rateLimiter,
            new BCryptPasswordEncoder(),
            () -> MagicProfileOtp.CODE,
            new SecureRandom(new byte[] {2}),
            Clock.fixed(NOW, ZoneOffset.UTC));
    adminService =
        new AdminPharmacyProfileService(
            profiles, audit, service, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    seed();
  }

  @Test
  void getProfileAndStaffAccess() {
    Map<String, Object> data = service.getProfile(owner());
    assertThat(data.get("code")).isEqualTo("PHM-0042");
    assertThat(data.get("operating_hours")).asList().isNotEmpty();
    assertThat(service.getProfile(staff()).get("pharmacy_id")).isEqualTo(PID.toString());
  }

  @Test
  void patchValidatesLogoPincodeAndUpdatesHours() {
    List<Map<String, Object>> hours = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("day_of_week", d);
      e.put("is_closed", d == 6);
      if (d != 6) {
        e.put("open_time", "09:00");
        e.put("close_time", "21:00");
      }
      hours.add(e);
    }
    Map<String, Object> data =
        service.patchProfile(
            owner(),
            Map.of(
                "tagline",
                "New tag",
                "logo_url",
                "https://cdn.example.com/new.jpg",
                "address",
                Map.of("pincode", "560034"),
                "operating_hours",
                hours));
    assertThat(data.get("updated_fields")).asList().contains("operating_hours");
    assertThat(profiles.hours).hasSize(7);
  }

  @Test
  void invalidLogoAndPincodeRejected() {
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("logo_url", "https://cdn/x.gif")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");
    assertThatThrownBy(
            () -> service.patchProfile(owner(), Map.of("address", Map.of("pincode", "999999"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");
  }

  @Test
  void bankValidationAndNotFound() {
    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "X",
                        "bank_name",
                        "Y",
                        "account_number",
                        "123456789",
                        "ifsc_code",
                        "BAD",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_IFSC");
    assertThatThrownBy(() -> service.getBankAccount(owner()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BANK_ACCOUNT_NOT_FOUND");
  }

  @Test
  void authGuardsAndStaffCannotPatch() {
    assertThatThrownBy(() -> service.patchProfile(null, Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.patchProfile(staff(), Map.of("tagline", "x")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void adminBusinessNameRequiresSuper() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> adminService.patchProfile(ops, PID, Map.of("business_name", "X"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void verifyContactInvalidOtpIncrementsAttempts() {
    profiles.record =
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord(
            profiles.record.id(),
            profiles.record.code(),
            profiles.record.businessName(),
            profiles.record.tagline(),
            profiles.record.logoUrl(),
            profiles.record.phone(),
            profiles.record.email(),
            "+919811100099",
            null,
            profiles.record.businessType(),
            profiles.record.address(),
            profiles.record.status(),
            profiles.record.plan(),
            profiles.record.gstin(),
            profiles.record.panNumber(),
            profiles.record.drugLicenceNumber(),
            profiles.record.fssaiNumber(),
            profiles.record.isGstRegistered(),
            profiles.record.eInvoicingEnabled(),
            profiles.record.tdsApplicable(),
            profiles.record.tcsApplicable(),
            profiles.record.gstinReverificationPending(),
            profiles.record.registeredPharmacistName(),
            profiles.record.createdAt(),
            profiles.record.updatedAt());
    FakeProfileOtps otps = new FakeProfileOtps();
    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    otps.insert(
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore.OtpRecord(
            Ids.newId(),
            PID,
            "PHONE",
            "+919811100099",
            new BCryptPasswordEncoder().encode("000000"),
            NOW.plusSeconds(600),
            0,
            NOW));
    PharmacyProfileService svc =
        new PharmacyProfileService(
            profiles,
            new FakeRegistrationStore(),
            new FakeChangeRequests(),
            otps,
            new FakePincodes(),
            (a, b, c) -> new PennyDropResult("RZP", "PENDING"),
            new AesGcmCipher(AES_KEY),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rl,
            new BCryptPasswordEncoder(),
            () -> "000000",
            new SecureRandom(new byte[] {3}),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> svc.verifyContact(owner(), "PHONE", "111111"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
  }

  private void seed() {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("flat", "12");
    address.put("area", "Koramangala");
    address.put("city", "Bengaluru");
    address.put("state", "Karnataka");
    address.put("pincode", "560034");
    profiles.record =
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord(
            PID,
            "PHM-0042",
            "Sharma Medical Store",
            "tag",
            "https://cdn.example.com/logo.png",
            "+919876543210",
            "owner@nammamedmate.test",
            null,
            null,
            "PHARMACY",
            address,
            "ACTIVE",
            "GROWTH",
            "29AABPP1234F1ZZ",
            "AABPP1234F",
            "DL",
            "11223344556677",
            true,
            false,
            false,
            true,
            false,
            "Pharmacist",
            NOW,
            NOW);
    profiles.hours.add(
        new com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore
            .OperatingHoursRecord(
            Ids.newId(),
            PID,
            0,
            java.time.LocalTime.of(9, 0),
            java.time.LocalTime.of(21, 0),
            false));
    pincodes.put(
        "560034",
        new com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore.PincodeRecord(
            "560034", "29", "KA", true));
  }

  private static MedmatePrincipal owner() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j");
  }

  private static MedmatePrincipal staff() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, PID, TokenScope.FULL, "j");
  }
}
