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
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort.PennyDropResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore.PincodeRecord;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.pharmacy.domain.OperatingHoursValidator;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PharmacyProfileBranchCoverageTest {

  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final byte[] AES_KEY = new byte[32];

  private FakeProfileStore profiles;
  private TrackingRegistrationStore pharmacies;
  private FakePincodes pincodes;
  private RateLimiter rateLimiter;
  private PharmacyProfileService service;
  private AdminPharmacyProfileService adminService;

  @BeforeEach
  void setUp() throws Exception {
    profiles = new FakeProfileStore();
    pharmacies = new TrackingRegistrationStore();
    pincodes = new FakePincodes();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    seedProfile();
    service = buildService(new FakeProfileOtps());
    adminService =
        new AdminPharmacyProfileService(
            profiles, new FakeAudit(), service, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private PharmacyProfileService buildService(FakeProfileOtps otps) throws Exception {
    return new PharmacyProfileService(
        profiles,
        pharmacies,
        new FakeChangeRequests(),
        otps,
        pincodes,
        (a, b, c) -> new PennyDropResult("RZP", "PENDING"),
        new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {1})),
        new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
        rateLimiter,
        new BCryptPasswordEncoder(),
        () -> MagicProfileOtp.CODE,
        new SecureRandom(new byte[] {2}),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void patchProfileEmailAndConflicts() {
    service.patchProfile(owner(), Map.of("email", "new@nammamedmate.test"));
    assertThat(profiles.get().pendingEmail()).isEqualTo("new@nammamedmate.test");
    pharmacies.phoneExists = true;
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("phone", "+919811100088")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHONE_ALREADY_REGISTERED");
    pharmacies.emailExists = true;
    assertThatThrownBy(
            () -> service.patchProfile(owner(), Map.of("email", "taken@nammamedmate.test")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_REGISTERED");
  }

  @Test
  void patchTaxBranches() {
    Map<String, Object> data =
        service.patchTax(
            owner(),
            Map.of(
                "pan_number",
                "AABPP1234F",
                "drug_licence_number",
                "DL-1",
                "fssai_number",
                "11223344556677",
                "is_gst_registered",
                true,
                "e_invoicing_enabled",
                true,
                "tds_applicable",
                true,
                "tcs_applicable",
                false,
                "registered_pharmacist_name",
                "Dr X"));
    assertThat(data.get("re_verification_triggered")).isEqualTo(false);
    assertThatThrownBy(() -> service.patchTax(owner(), Map.of("fssai_number", "bad")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.patchTax(owner(), Map.of("drug_licence_number", "x".repeat(51))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void verifyEmailAndGuards() {
    service.patchProfile(owner(), Map.of("email", "new@nammamedmate.test"));
    assertThat(service.verifyContact(owner(), "EMAIL", MagicProfileOtp.CODE).get("verified"))
        .isEqualTo(true);
    assertThatThrownBy(() -> service.verifyContact(owner(), "EMAIL", MagicProfileOtp.CODE))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_PENDING_VERIFICATION");
    assertThatThrownBy(() -> service.verifyContact(owner(), "BAD", "123456"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void bankAccountAdminReadAndInvalidAccount() {
    service.saveBankAccount(
        owner(),
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
            "SAVINGS"));
    assertThat(service.getBankAccount(owner())).containsKey("bank_account_id");
    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "H",
                        "bank_name",
                        "B",
                        "account_number",
                        "12",
                        "ifsc_code",
                        "HDFC0001234",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACCOUNT_NUMBER");
    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
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
                        "BAD")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminUpdatesAllFields() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    adminService.patchProfile(
        superAdmin,
        PID,
        Map.of(
            "business_name",
            "Admin Renamed",
            "phone",
            "+919811100077",
            "email",
            "admin@nammamedmate.test",
            "logo_url",
            "https://cdn.example.com/x.png",
            "address",
            Map.of("city", "Mumbai")),
        "ip");
    adminService.patchProfile(ops, PID, Map.of("tagline", "ops tag"), "ip");
    assertThat(profiles.get().businessName()).isEqualTo("Admin Renamed");
    service.saveBankAccount(
        owner(),
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
    assertThat(adminService.getBankAccount(superAdmin, PID)).isNotNull();
    assertThatThrownBy(() -> adminService.getBankAccount(null, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void rateLimitAndOtpPaths() throws Exception {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.getProfile(owner()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);

    FakeProfileOtps otps = new FakeProfileOtps();
    PharmacyProfileService svc = buildService(otps);
    svc.patchProfile(owner(), Map.of("phone", "+919811100088"));
    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            PID,
            "PHONE",
            "+919811100088",
            otps.latest.otpHash(),
            NOW.minusSeconds(60),
            0,
            NOW);
    assertThatThrownBy(() -> svc.verifyContact(owner(), "PHONE", MagicProfileOtp.CODE))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_EXPIRED");

    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            PID,
            "PHONE",
            "+919811100088",
            otps.latest.otpHash(),
            NOW.plusSeconds(600),
            5,
            NOW);
    assertThatThrownBy(() -> svc.verifyContact(owner(), "PHONE", "000000"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_LOCKED");
  }

  @Test
  void operatingHoursMoreBranches() {
    List<Map<String, Object>> missingDay = new ArrayList<>();
    missingDay.add(
        Map.of("day_of_week", 0, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(missingDay))
        .isInstanceOf(AppException.class);
    List<Map<String, Object>> badDow = List.of(Map.of("is_closed", false));
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(badDow))
        .isInstanceOf(AppException.class);
    List<Map<String, Object>> badRange = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      badRange.add(Map.of("day_of_week", d, "is_closed", true));
    }
    badRange.set(0, Map.of("day_of_week", 0, "is_closed", false));
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(badRange))
        .isInstanceOf(AppException.class);
    List<Map<String, Object>> dup = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      dup.add(
          Map.of(
              "day_of_week", 0, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(dup))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                OperatingHoursValidator.requireValid(
                    List.of(Map.of("day_of_week", 9, "is_closed", true))))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                OperatingHoursValidator.requireValid(
                    List.of(
                        Map.of(
                            "day_of_week",
                            0,
                            "open_time",
                            "bad",
                            "close_time",
                            "18:00",
                            "is_closed",
                            false))))
        .isInstanceOf(AppException.class);
  }

  @Test
  void remainingValidationBranches() {
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("business_name", "X")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("tagline", "x".repeat(201))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("phone", "bad")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE");
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("email", "not-an-email")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchTax(owner(), Map.of("pan_number", "BAD")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PAN");
    assertThatThrownBy(() -> service.patchTax(owner(), Map.of("gstin", "INVALIDGSTIN00")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GSTIN");
    assertThatThrownBy(
            () -> service.patchTax(owner(), Map.of("registered_pharmacist_name", "x".repeat(101))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    FakeProfileOtps emptyOtps = new FakeProfileOtps();
    profiles.record =
        new ProfileRecord(
            profiles.record.id(),
            profiles.record.code(),
            profiles.record.businessName(),
            profiles.record.tagline(),
            profiles.record.logoUrl(),
            profiles.record.phone(),
            profiles.record.email(),
            "+919811100088",
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
    try {
      PharmacyProfileService svc = buildService(emptyOtps);
      assertThatThrownBy(() -> svc.verifyContact(owner(), "PHONE", MagicProfileOtp.CODE))
          .extracting(ex -> ((AppException) ex).code())
          .isEqualTo("OTP_NOT_FOUND");
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    List<Map<String, Object>> week = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      week.add(
          Map.of(
              "day_of_week", d, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    }
    adminService.patchProfile(ops, PID, Map.of("operating_hours", week), "ip");
    assertThatThrownBy(() -> adminService.patchProfile(ops, PID, Map.of("phone", "bad"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE");

    assertThat(service.getCompleteness(staff()).get("completeness_pct")).isNotNull();
    service.patchProfile(owner(), Map.of("phone", "+919876543210"));
    service.patchProfile(owner(), Map.of("business_name", "Sharma Medical Store"));
    assertThatThrownBy(() -> service.verifyContact(owner(), "PHONE", ""))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "bank_name",
                        "B",
                        "account_number",
                        "123456789",
                        "ifsc_code",
                        "HDFC0001234",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    adminService.patchProfile(
        superAdmin,
        PID,
        Map.of("business_name", "Super Admin Name", "logo_url", "https://cdn.example.com/new.png"),
        "ip");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.patchProfile(owner(), Map.of("tagline", "x")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    assertThatThrownBy(() -> service.getBankAccount(staff()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  private void seedProfile() {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("flat", "12");
    address.put("area", "Koramangala");
    address.put("city", "Bengaluru");
    address.put("state", "Karnataka");
    address.put("pincode", "560034");
    profiles.record =
        new ProfileRecord(
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
    for (int d = 0; d < 5; d++) {
      profiles.hours.add(
          new OperatingHoursRecord(
              Ids.newId(), PID, d, LocalTime.of(9, 0), LocalTime.of(21, 0), false));
    }
    pincodes.put("560034", new PincodeRecord("560034", "29", "KA", true));
  }

  private static MedmatePrincipal owner() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j");
  }

  private static MedmatePrincipal staff() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, PID, TokenScope.FULL, "j");
  }

  static final class TrackingRegistrationStore implements PharmacyRegistrationStore {
    boolean phoneExists;
    boolean emailExists;

    @Override
    public void insert(PharmacyRecord pharmacy) {}

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public boolean existsGstin(String gstin) {
      return false;
    }

    @Override
    public boolean existsPan(String pan) {
      return false;
    }

    @Override
    public boolean existsDrugLicence(String licence, String stateCode) {
      return false;
    }

    @Override
    public boolean existsPhone(String phone) {
      return phoneExists;
    }

    @Override
    public boolean existsEmail(String email) {
      return emailExists;
    }

    @Override
    public void markEmailVerified(UUID pharmacyId, Instant at) {}

    @Override
    public void updateStatus(
        UUID pharmacyId, String status, Instant kycSubmittedAt, Instant updatedAt) {}

    @Override
    public void activateAfterAutoKyc(UUID pharmacyId, UUID zoneId, Instant at) {}
  }
}
