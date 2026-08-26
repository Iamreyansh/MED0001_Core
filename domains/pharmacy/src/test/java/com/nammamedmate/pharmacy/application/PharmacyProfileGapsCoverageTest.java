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
import com.nammamedmate.pharmacy.application.PharmacyProfileBranchCoverageTest.TrackingRegistrationStore;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeAudit;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeChangeRequests;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakePincodes;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileOtps;
import com.nammamedmate.pharmacy.application.PharmacyProfileServiceTest.FakeProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort.PennyDropResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore.PincodeRecord;
import com.nammamedmate.pharmacy.application.port.out.ProfileContactNotifier;
import com.nammamedmate.pharmacy.domain.MagicProfileOtp;
import com.nammamedmate.pharmacy.domain.OperatingHoursValidator;
import com.nammamedmate.pharmacy.domain.ProfileCompleteness;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fills remaining JaCoCo branch gaps for profile services. */
class PharmacyProfileGapsCoverageTest {

  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final byte[] AES_KEY = new byte[32];

  private FakeProfileStore profiles;
  private TrackingRegistrationStore pharmacies;
  private FakePincodes pincodes;
  private FakeProfileOtps otps;
  private InMemoryOutboxStore outbox;
  private RateLimiter rateLimiter;
  private PharmacyProfileService service;
  private AdminPharmacyProfileService adminService;

  @BeforeEach
  void setUp() throws Exception {
    profiles = new FakeProfileStore();
    pharmacies = new TrackingRegistrationStore();
    pincodes = new FakePincodes();
    otps = new FakeProfileOtps();
    outbox = new InMemoryOutboxStore();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    service = buildService(null);
    adminService =
        new AdminPharmacyProfileService(
            profiles, new FakeAudit(), service, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    seedProfile();
  }

  private PharmacyProfileService buildService(java.util.function.Supplier<String> otpGen)
      throws Exception {
    return new PharmacyProfileService(
        profiles,
        pharmacies,
        new FakeChangeRequests(),
        otps,
        pincodes,
        (a, b, c) -> new PennyDropResult("RZP-PENNY-GAP", "PENDING"),
        new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {3})),
        new OutboxPublisher(outbox, new ObjectMapper()),
        rateLimiter,
        new BCryptPasswordEncoder(),
        otpGen,
        new SecureRandom(new byte[] {4}),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void patchProfileSuccessPathsAndMessages() {
    Map<String, Object> onlyTagline = service.patchProfile(owner(), Map.of("tagline", "New tag"));
    assertThat(onlyTagline.get("message")).isEqualTo("Profile updated successfully.");

    Map<String, Object> approval =
        service.patchProfile(owner(), Map.of("business_name", "Renamed Store Pvt Ltd"));
    assertThat(approval.get("message").toString()).contains("pending admin approval");

    service.patchProfile(owner(), Map.of("phone", "+919811100055"));
    Map<String, Object> verifyMsg =
        service.patchProfile(owner(), Map.of("email", "x@nammamedmate.test"));
    assertThat(verifyMsg.get("message").toString()).contains("Verify");

    List<Map<String, Object>> week = fullWeek();
    service.patchProfile(
        owner(), Map.of("operating_hours", week, "logo_url", "https://cdn/x.jpeg"));
    assertThat(service.getProfile(owner())).containsKeys("operating_hours", "tax", "bank_account");
  }

  @Test
  void patchTaxGstinReverifyAndSameGstin() {
    Map<String, Object> changed = service.patchTax(owner(), Map.of("gstin", "29AABPP1235F1ZY"));
    assertThat(changed.get("re_verification_triggered")).isEqualTo(true);

    Map<String, Object> same = service.patchTax(owner(), Map.of("gstin", profiles.get().gstin()));
    assertThat(same.get("re_verification_triggered")).isEqualTo(false);
  }

  @Test
  void inactivePharmacyAndAuthGuards() {
    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            Map.of(),
            "KYC_SUBMITTED",
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
            NOW,
            NOW);
    assertThatThrownBy(() -> service.getProfile(owner()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_ACTIVE");

    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, PID, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getProfile(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            Map.of(),
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
            NOW,
            NOW);

    MedmatePrincipal ownerNoCtx =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getBankAccount(ownerNoCtx))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
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
    assertThat(adminService.getBankAccount(finance, PID)).containsKey("penny_drop_reference");
  }

  @Test
  void bankValidationAndStalePennyDrop() {
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
                        "BADIFSC",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_IFSC");

    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "x".repeat(101),
                        "bank_name",
                        "B",
                        "account_number",
                        "123456789",
                        "ifsc_code",
                        "HDFC0001234",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.getBankAccount(owner()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BANK_ACCOUNT_NOT_FOUND");

    UUID bankId = Ids.newId();
    profiles.bank =
        new BankAccountRecord(
            bankId,
            PID,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "PENDING",
            "RZP-STALE",
            null,
            NOW.minusSeconds(90_000),
            NOW.minusSeconds(90_000));
    service.expireStalePennyDrops();
    assertThat(profiles.bank.verificationStatus()).isEqualTo("FAILED");
  }

  @Test
  void verifyContactInvalidOtpIncrementsAttempts() throws Exception {
    PharmacyProfileService svc = buildService(() -> "654321");
    svc.patchProfile(owner(), Map.of("phone", "+919811100066"));
    assertThatThrownBy(() -> svc.verifyContact(owner(), "PHONE", "000000"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
    assertThat(otps.latest.attempts()).isEqualTo(1);
  }

  @Test
  void randomOtpGenerationWithoutSupplier() throws Exception {
    PharmacyProfileService svc = buildService(null);
    svc.patchProfile(owner(), Map.of("email", "rand@nammamedmate.test"));
    assertThat(otps.latest).isNotNull();
  }

  @Test
  void addressPincodeValidation() {
    pincodes.map.clear();
    assertThatThrownBy(
            () ->
                service.patchProfile(
                    owner(), Map.of("address", Map.of("pincode", "999999", "flat", "1"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");
    assertThatThrownBy(
            () -> service.patchProfile(owner(), Map.of("address", Map.of("pincode", "12ab"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");
  }

  @Test
  void adminPatchGuardsAndNotFound() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> adminService.patchProfile(ops, PID, Map.of("business_name", "X"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    UUID missing = Ids.newId();
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> adminService.patchProfile(superAdmin, missing, Map.of("tagline", "t"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(
            () -> adminService.patchProfile(superAdmin, PID, Map.of("tagline", "t"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);

    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> adminService.getBankAccount(customer, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> adminService.getBankAccount(superAdmin, missing))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void operatingHoursValidatorExhaustive() {
    List<Map<String, Object>> week = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      week.add(
          Map.of(
              "day_of_week", d, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    }
    week.set(2, null);
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(week))
        .isInstanceOf(AppException.class);

    List<Map<String, Object>> stringDow = new ArrayList<>(week);
    stringDow.set(2, Map.of("day_of_week", "0", "is_closed", true));
    for (int i = 0; i < 7; i++) {
      if (stringDow.get(i) == null) {
        stringDow.set(
            i,
            Map.of(
                "day_of_week", i, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
      }
    }
    stringDow.set(3, Map.of("day_of_week", "bad", "is_closed", true));
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(stringDow))
        .isInstanceOf(AppException.class);

    assertThat(OperatingHoursValidator.dayName(-1)).isEqualTo("Unknown");
    assertThat(OperatingHoursValidator.dayName(7)).isEqualTo("Unknown");
    List<Map<String, Object>> badDay = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      badDay.add(
          Map.of(
              "day_of_week",
              d == 3 ? 8 : d,
              "open_time",
              "09:00",
              "close_time",
              "18:00",
              "is_closed",
              false));
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(badDay))
        .isInstanceOf(AppException.class);
    assertThat(MagicProfileOtp.isTestPhone("+919999900012")).isTrue();
    assertThat(MagicProfileOtp.matches("SMS", "+919811100001", MagicProfileOtp.CODE)).isFalse();
  }

  @Test
  void autowiredConstructorAndRemainingServiceBranches() throws Exception {
    PharmacyProfileService autowired =
        new PharmacyProfileService(
            profiles,
            pharmacies,
            new FakeChangeRequests(),
            otps,
            pincodes,
            (a, b, c) -> new PennyDropResult("RZP", "PENDING"),
            new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {5})),
            new OutboxPublisher(outbox, new ObjectMapper()),
            rateLimiter,
            new BCryptPasswordEncoder(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(autowired.getProfile(owner())).isNotEmpty();

    java.util.concurrent.atomic.AtomicInteger emailed =
        new java.util.concurrent.atomic.AtomicInteger();
    ProfileContactNotifier notifier =
        new ProfileContactNotifier() {
          @Override
          public void sendEmailOtp(String email, String otp) {
            emailed.incrementAndGet();
          }

          @Override
          public void sendSmsOtp(String phone, String otp) {}
        };
    PharmacyProfileService withNotifier =
        new PharmacyProfileService(
            profiles,
            pharmacies,
            new FakeChangeRequests(),
            otps,
            pincodes,
            (a, b, c) -> new PennyDropResult("RZP", "PENDING"),
            new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {5})),
            new OutboxPublisher(outbox, new ObjectMapper()),
            rateLimiter,
            new BCryptPasswordEncoder(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            notifier);
    withNotifier.patchProfile(owner(), Map.of("email", "notify@nammamedmate.test"));
    assertThat(emailed.get()).isEqualTo(1);

    PharmacyProfileService nullNotifier =
        new PharmacyProfileService(
            profiles,
            pharmacies,
            new FakeChangeRequests(),
            otps,
            pincodes,
            (a, b, c) -> new PennyDropResult("RZP", "PENDING"),
            new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {5})),
            new OutboxPublisher(outbox, new ObjectMapper()),
            rateLimiter,
            new BCryptPasswordEncoder(),
            () -> MagicProfileOtp.CODE,
            new SecureRandom(new byte[] {6}),
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    assertThat(nullNotifier.getProfile(owner())).isNotEmpty();

    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            null,
            "tag",
            "https://cdn.example.com/logo.png",
            "+919876543210",
            "owner@nammamedmate.test",
            null,
            null,
            "PHARMACY",
            null,
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
            "Pharmacist",
            null,
            null);
    autowired.patchProfile(owner(), Map.of("business_name", "Brand New Name"));
    autowired.patchProfile(owner(), Map.of("email", "owner@nammamedmate.test"));
    autowired.patchProfile(owner(), Map.of("address", Map.of("flat", "1", "pincode", "560034")));

    assertThatThrownBy(
            () ->
                autowired.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "H",
                        "bank_name",
                        "x".repeat(101),
                        "account_number",
                        "123456789",
                        "ifsc_code",
                        "HDFC0001234",
                        "account_type",
                        "CURRENT")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID bankId = Ids.newId();
    profiles.bank =
        new BankAccountRecord(
            bankId,
            PID,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "VERIFIED",
            "RZP",
            NOW,
            NOW,
            NOW);
    Map<String, Object> bankMap = autowired.getBankAccountForAdmin(PID);
    assertThat(bankMap.get("verified_at")).isNotNull();

    PharmacyProfileService randomOtpSvc = buildService(null);
    randomOtpSvc.patchProfile(owner(), Map.of("phone", "+919876543211"));
    OtpRecord otp = otps.latest;
    BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    otps.latest =
        new OtpRecord(
            otp.id(), PID, "PHONE", "+919876543211", enc.encode("999888"), otp.expiresAt(), 0, NOW);
    assertThatThrownBy(() -> randomOtpSvc.verifyContact(owner(), "PHONE", "000000"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
    otps.latest =
        new OtpRecord(
            otp.id(), PID, "PHONE", "+919876543211", enc.encode("999888"), otp.expiresAt(), 0, NOW);
    assertThat(randomOtpSvc.verifyContact(owner(), "PHONE", "999888").get("verified"))
        .isEqualTo(true);
  }

  @Test
  void adminRemainingBranches() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    adminService.patchProfile(
        superAdmin, PID, Map.of("business_name", profiles.get().businessName()), "ip");
    adminService.patchProfile(
        superAdmin,
        PID,
        Map.of(
            "address",
            Map.of("flat", "99", "area", "A", "city", "C", "state", "S", "pincode", "560034")),
        "ip");
    assertThatThrownBy(() -> adminService.patchProfile(null, PID, Map.of("tagline", "t"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> adminService.getBankAccount(null, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    Map<String, Object> nullPhone = new LinkedHashMap<>();
    nullPhone.put("phone", null);
    assertThatThrownBy(() -> adminService.patchProfile(superAdmin, PID, nullPhone, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE");
    Map<String, Object> nullBiz = new LinkedHashMap<>();
    nullBiz.put("business_name", null);
    adminService.patchProfile(superAdmin, PID, nullBiz, "ip");
    adminService.patchProfile(ops, PID, Map.of("address", "not-map"), "ip");
    adminService.patchProfile(ops, PID, Map.of("operating_hours", "not-list"), "ip");
    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            null,
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
            NOW,
            NOW);
    adminService.patchProfile(ops, PID, Map.of("address", Map.of("flat", "1")), "ip");
    assertThatThrownBy(
            () -> adminService.patchProfile(superAdmin, PID, Map.of("business_name", "X"), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                adminService.patchProfile(
                    superAdmin, PID, Map.of("business_name", "x".repeat(121)), "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void operatingHoursNullEntryAndOpenWithoutTimes() {
    List<Map<String, Object>> withNull = new ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(withNull))
        .isInstanceOf(AppException.class);

    List<Map<String, Object>> openNoClose = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("day_of_week", d);
      e.put("is_closed", false);
      if (d == 0) {
        e.put("open_time", "09:00");
      } else {
        e.put("open_time", "09:00");
        e.put("close_time", "18:00");
      }
      openNoClose.add(e);
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(openNoClose))
        .isInstanceOf(AppException.class);
  }

  @Test
  void profileCompletenessEdgeCases() {
    ProfileRecord partial =
        new ProfileRecord(
            PID,
            "C",
            "B",
            null,
            null,
            "+91",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            null,
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
            null,
            null);
    List<OperatingHoursRecord> threeOpen = new ArrayList<>();
    for (int d = 0; d < 3; d++) {
      threeOpen.add(
          new OperatingHoursRecord(
              Ids.newId(), PID, d, LocalTime.of(9, 0), LocalTime.of(18, 0), false));
    }
    BankAccountRecord pendingBank =
        new BankAccountRecord(
            Ids.newId(),
            PID,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "PENDING",
            "RZP",
            null,
            NOW,
            NOW);
    ProfileCompleteness.Result r = ProfileCompleteness.calculate(partial, threeOpen, pendingBank);
    assertThat(r.missingFields()).isNotEmpty();
    assertThat(r.completedFields()).contains("phone", "email", "business_name");

    profiles.hours.add(new OperatingHoursRecord(Ids.newId(), PID, 6, null, null, true));
    Map<String, Object> profile = service.getProfile(owner());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hours = (List<Map<String, Object>>) profile.get("operating_hours");
    assertThat(hours).isNotEmpty();
  }

  @Test
  void remainingPharmacyProfileServiceBranches() throws Exception {
    Map<String, Object> businessNull = new LinkedHashMap<>();
    businessNull.put("business_name", null);
    service.patchProfile(owner(), businessNull);

    service.patchProfile(owner(), Map.of("address", "not-a-map"));
    service.patchProfile(owner(), Map.of("operating_hours", "not-a-list"));
    service.patchProfile(
        owner(),
        Map.of(
            "address",
            Map.of("flat", "1", "area", "A", "city", "C", "state", "S", "pincode", "560034")));

    Map<String, Object> noPincodeAddr = new LinkedHashMap<>();
    noPincodeAddr.put("address", Map.of("flat", "1"));
    service.patchProfile(owner(), noPincodeAddr);

    assertThatThrownBy(() -> service.getBankAccountForAdmin(PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BANK_ACCOUNT_NOT_FOUND");

    assertThatThrownBy(() -> service.verifyContact(owner(), null, "123456"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verifyContact(owner(), "PHONE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    profiles.record =
        new ProfileRecord(
            profiles.record.id(),
            profiles.record.code(),
            profiles.record.businessName(),
            profiles.record.tagline(),
            profiles.record.logoUrl(),
            profiles.record.phone(),
            profiles.record.email(),
            profiles.record.pendingPhone(),
            "pending@nammamedmate.test",
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
    assertThatThrownBy(() -> service.verifyContact(owner(), "EMAIL", MagicProfileOtp.CODE))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_NOT_FOUND");

    assertThatThrownBy(
            () -> service.patchProfile(owner(), Map.of("business_name", "x".repeat(121))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> nullEmail = new LinkedHashMap<>();
    nullEmail.put("email", null);
    assertThatThrownBy(() -> service.patchProfile(owner(), nullEmail))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> nullPincodeAddr = new LinkedHashMap<>();
    Map<String, Object> addr = new LinkedHashMap<>();
    addr.put("pincode", null);
    nullPincodeAddr.put("address", addr);
    assertThatThrownBy(() -> service.patchProfile(owner(), nullPincodeAddr))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");

    profiles.record = null;
    assertThatThrownBy(() -> service.getProfile(owner()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    seedProfile();

    MedmatePrincipal staffNoCtx =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getProfile(staffNoCtx))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.getBankAccount(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getBankAccount(superAdmin))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    PharmacyProfileService magicOtpSvc = buildService(null);
    magicOtpSvc.patchProfile(owner(), Map.of("phone", "+919811100001"));
    magicOtpSvc.patchProfile(owner(), Map.of("email", "magic@nammamedmate.test"));

    PharmacyProfileService genOtpSvc = buildService(() -> "444444");
    genOtpSvc.patchProfile(owner(), Map.of("phone", "+919876543299"));

    java.lang.reflect.Method merge =
        PharmacyProfileService.class.getDeclaredMethod("mergeAddress", Map.class, Map.class);
    merge.setAccessible(true);
    Map<Object, Object> patch = new LinkedHashMap<>();
    patch.put(null, "ignored");
    @SuppressWarnings("unchecked")
    Map<String, Object> merged = (Map<String, Object>) merge.invoke(null, null, patch);
    assertThat(merged).isEmpty();

    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            "pending@nammamedmate.test",
            "PHARMACY",
            profiles.record.address(),
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
            null,
            null);
    assertThatThrownBy(() -> service.verifyContact(owner(), "PHONE", MagicProfileOtp.CODE))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_PENDING_VERIFICATION");

    java.lang.reflect.Method requireBankRead =
        PharmacyProfileService.class.getDeclaredMethod("requireBankRead", MedmatePrincipal.class);
    requireBankRead.setAccessible(true);
    requireBankRead.invoke(null, owner());
    for (AuthRole role :
        List.of(AuthRole.ADMIN_FINANCE, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS)) {
      assertThatThrownBy(
              () -> {
                try {
                  requireBankRead.invoke(
                      null, new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "j"));
                } catch (java.lang.reflect.InvocationTargetException ex) {
                  throw ex.getCause();
                }
              })
          .isInstanceOf(AppException.class)
          .extracting(ex -> ((AppException) ex).code())
          .isEqualTo("FORBIDDEN");
    }

    java.lang.reflect.Method nextOtp =
        PharmacyProfileService.class.getDeclaredMethod("nextOtp", String.class, String.class);
    nextOtp.setAccessible(true);
    assertThat(nextOtp.invoke(buildService(null), "EMAIL", "x@nammamedmate.test"))
        .isEqualTo(MagicProfileOtp.CODE);
    assertThat(nextOtp.invoke(buildService(null), "PHONE", "+919811100001"))
        .isEqualTo(MagicProfileOtp.CODE);
    assertThat(nextOtp.invoke(buildService(() -> "555555"), "PHONE", "+919876543288"))
        .isEqualTo("555555");

    assertThatThrownBy(
            () ->
                service.saveBankAccount(
                    owner(),
                    Map.of(
                        "account_holder",
                        "   ",
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
    seedProfile();
  }

  @Test
  void lastCoverageGaps() throws Exception {
    adminService.patchProfile(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
        PID,
        Map.of("business_name", "Sharma Medical Store"),
        "ip");

    assertThatThrownBy(
            () ->
                adminService.patchProfile(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    PID,
                    Map.of("tagline", "x"),
                    "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            "  ",
            null,
            "PHARMACY",
            Map.of(),
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
            null,
            null);
    assertThatThrownBy(() -> service.verifyContact(owner(), "PHONE", MagicProfileOtp.CODE))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_PENDING_VERIFICATION");

    profiles.hours.clear();
    profiles.hours.add(
        new OperatingHoursRecord(Ids.newId(), PID, 0, LocalTime.of(9, 0), null, false));
    profiles.hours.add(
        new OperatingHoursRecord(Ids.newId(), PID, 1, LocalTime.of(9, 0), null, true));
    profiles.record =
        new ProfileRecord(
            PID,
            profiles.record.code(),
            profiles.record.businessName(),
            profiles.record.tagline(),
            profiles.record.logoUrl(),
            profiles.record.phone(),
            profiles.record.email(),
            profiles.record.pendingPhone(),
            profiles.record.pendingEmail(),
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
            NOW,
            null);
    Map<String, Object> profileMap = service.getProfile(owner());
    assertThat(profileMap.get("created_at")).isNotNull();
    assertThat(profileMap.get("updated_at")).isNull();

    service.patchProfile(owner(), Map.of("address", Map.of("flat", "only")));

    assertThatThrownBy(
            () -> service.patchProfile(owner(), Map.of("email", "a".repeat(250) + "@x.com")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    java.lang.reflect.Method nextOtp =
        PharmacyProfileService.class.getDeclaredMethod("nextOtp", String.class, String.class);
    nextOtp.setAccessible(true);
    assertThat(nextOtp.invoke(buildService(null), "EMAIL", "real@other.com")).isNotNull();

    assertThat(MagicProfileOtp.matches(null, null, MagicProfileOtp.CODE)).isFalse();
    assertThat(MagicProfileOtp.matches("EMAIL", null, MagicProfileOtp.CODE)).isFalse();

    List<Map<String, Object>> badTime = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      badTime.add(
          Map.of(
              "day_of_week",
              d == 2 ? 10 : d,
              "open_time",
              "09:00",
              "close_time",
              "18:00",
              "is_closed",
              false));
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(badTime))
        .isInstanceOf(AppException.class);

    List<Map<String, Object>> invalidParse = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("day_of_week", d);
      entry.put("is_closed", false);
      entry.put("open_time", d == 1 ? "" : "09:00");
      entry.put("close_time", "18:00");
      invalidParse.add(entry);
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(invalidParse))
        .isInstanceOf(AppException.class);

    java.lang.reflect.Method mergeAdmin =
        AdminPharmacyProfileService.class.getDeclaredMethod("merge", Map.class, Map.class);
    mergeAdmin.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> mergedAdmin =
        (Map<String, Object>) mergeAdmin.invoke(null, null, Map.of("flat", "1"));
    assertThat(mergedAdmin).containsEntry("flat", "1");

    assertThatThrownBy(
            () ->
                adminService.getBankAccount(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    Map<String, Object> nullBizName = new LinkedHashMap<>();
    nullBizName.put("business_name", null);
    adminService.patchProfile(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
        PID,
        nullBizName,
        "ip");

    assertThatThrownBy(
            () ->
                adminService.getBankAccount(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j"),
                    PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    profiles.hours.clear();
    profiles.hours.add(new OperatingHoursRecord(Ids.newId(), PID, 0, null, null, false));
    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            "Biz",
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            Map.of(),
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
            null,
            NOW);
    assertThat(service.getProfile(owner()).get("created_at")).isNull();
    profiles.record =
        new ProfileRecord(
            profiles.record.id(),
            profiles.record.code(),
            profiles.record.businessName(),
            profiles.record.tagline(),
            profiles.record.logoUrl(),
            profiles.record.phone(),
            profiles.record.email(),
            profiles.record.pendingPhone(),
            profiles.record.pendingEmail(),
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
            NOW,
            NOW);
    assertThat(service.getProfile(owner()).get("created_at")).isNotNull();

    profiles.bank =
        new BankAccountRecord(
            Ids.newId(),
            PID,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "VERIFIED",
            "RZP",
            NOW,
            NOW,
            NOW);
    assertThat(service.getProfile(owner()).get("bank_account")).isNotNull();

    MedmatePrincipal opsAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> adminService.getBankAccount(opsAdmin, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    profiles.record =
        new ProfileRecord(
            PID,
            "PHM",
            null,
            null,
            null,
            "+919876543210",
            "e@t.com",
            null,
            null,
            "PHARMACY",
            Map.of(),
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
            NOW,
            NOW);
    adminService.patchProfile(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
        PID,
        Map.of("business_name", "First Admin Name"),
        "ip");
    adminService.patchProfile(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
        PID,
        Map.of("business_name", "First Admin Name"),
        "ip");

    List<Map<String, Object>> weekNeg = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      weekNeg.add(
          Map.of(
              "day_of_week",
              d == 4 ? -1 : d,
              "open_time",
              "09:00",
              "close_time",
              "18:00",
              "is_closed",
              false));
    }
    assertThatThrownBy(() -> OperatingHoursValidator.requireValid(weekNeg))
        .isInstanceOf(AppException.class);

    seedProfile();
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

  private static List<Map<String, Object>> fullWeek() {
    List<Map<String, Object>> week = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      week.add(
          Map.of(
              "day_of_week", d, "open_time", "09:00", "close_time", "18:00", "is_closed", false));
    }
    return week;
  }

  private static MedmatePrincipal owner() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j");
  }
}
