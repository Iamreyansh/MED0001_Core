package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.AddressCommand;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.RegisterCommand;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakeAudit;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakeOtps;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakeOwners;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakePharmacies;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakePincodes;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.FakeSessions;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.MutableClock;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationServiceTest.RecordingEmail;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore.OwnerCreate;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.domain.MagicRegistrationOtp;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PharmacyRegistrationServiceMoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

  private MutableClock clock;
  private FakePharmacies pharmacies;
  private FakeOwners owners;
  private FakeOtps otps;
  private FakeSessions sessions;
  private FakePincodes pincodes;
  private FakeAudit audit;
  private RecordingEmail email;
  private InMemoryRateLimiter limiter;
  private PharmacyRegistrationService service;
  private PharmacyRegistrationService randomOtpService;
  private OutboxPublisher outbox;
  private BCryptPasswordEncoder staffEnc;
  private BCryptPasswordEncoder otpEnc;
  private Rs256JwtService jwt;

  @BeforeEach
  void setUp() throws Exception {
    clock = new MutableClock(NOW);
    pharmacies = new FakePharmacies();
    owners = new FakeOwners();
    otps = new FakeOtps();
    sessions = new FakeSessions();
    pincodes = new FakePincodes();
    audit = new FakeAudit();
    email = new RecordingEmail();
    limiter = new InMemoryRateLimiter(clock);
    outbox = new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper());
    staffEnc = new BCryptPasswordEncoder(12);
    otpEnc = new BCryptPasswordEncoder(10);
    var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    var pair = gen.generateKeyPair();
    jwt =
        new Rs256JwtService(
            pair.getPrivate(),
            pair.getPublic(),
            new InMemoryTokenRevocationStore(clock),
            clock,
            900);
    service =
        new PharmacyRegistrationService(
            pharmacies,
            owners,
            otps,
            sessions,
            pincodes,
            audit,
            email,
            outbox,
            staffEnc,
            otpEnc,
            jwt,
            limiter,
            clock,
            new java.security.SecureRandom(),
            () -> "654321");
    randomOtpService =
        new PharmacyRegistrationService(
            pharmacies,
            owners,
            otps,
            sessions,
            pincodes,
            audit,
            email,
            outbox,
            staffEnc,
            otpEnc,
            jwt,
            limiter,
            clock,
            new java.security.SecureRandom(),
            null);
  }

  @Test
  void coversRemainingRegisterBranches() {
    assertThatThrownBy(() -> service.register(null, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000030",
                        "noaddr@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        null,
                        "29AABPP1234F1ZZ",
                        "L30",
                        null,
                        "AABPP1234F",
                        "560001"),
                    "ip-na"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    owners.created.add(
        new OwnerCreate(
            Ids.newId(),
            "X",
            "seed@nammamedmate.test",
            "+919700000040",
            "h",
            Ids.newId(),
            null,
            NOW));
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(
                        "other@nammamedmate.test",
                        "+919700000040",
                        "L40",
                        "29AABPP1234F2ZY",
                        "AABPP1235F"),
                    "ip-phone"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHONE_ALREADY_REGISTERED");

    service.register(
        cmd("a1@nammamedmate.test", "+919700000001", "L1", "29AABPP1234F1ZZ", "AABPP1234F"),
        "ip-a");
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(
                        "a2@nammamedmate.test",
                        "+919700000001",
                        "L2",
                        "29AABPP1234F2ZY",
                        "AABPP1235F"),
                    "ip-b"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    owners.created.clear();
    pharmacies.byId.clear();
    UUID existing = Ids.newId();
    pharmacies.insert(
        minimal(existing, "29AABPP1234F3ZX", "AABPP1236F", "Lx", "+919700000099", "z@x.com"));
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(
                        "fresh@nammamedmate.test",
                        "+919700000002",
                        "L3",
                        "29AABPP1234F3ZX",
                        "ABCPA1234A"),
                    "ip-c"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GSTIN_ALREADY_REGISTERED");

    pharmacies.byId.clear();
    pharmacies.insert(
        minimal(Ids.newId(), "29AABPP1234F4ZW", "ABCPA1234A", "Ly", "+919700000098", "z2@x.com"));
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(
                        "fresh2@nammamedmate.test",
                        "+919700000003",
                        "L4",
                        "29AABPP1234F5ZV",
                        "ABCPA1234A"),
                    "ip-d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAN_ALREADY_REGISTERED");

    // IP rate limit: 5 then 6th fails
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire("pharmacy:register:ip:flood", 5, 60);
    }
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(
                        "flood@nammamedmate.test",
                        "+919700000050",
                        "LF",
                        "29AABPP1234F6ZU",
                        "AABPP1234F"),
                    "flood"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void verifyAndResendAndStatusEdges() {
    service.register(
        cmd("ve@nammamedmate.test", "+919700000010", "LV1", "29ABCPA1234A1ZL", "ABCPA1234A"),
        "vip");

    assertThatThrownBy(() -> service.verifyEmail(null, null, "vip", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(
            () -> service.verifyEmail("missing@nammamedmate.test", "123456", "vip", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_FOUND");

    assertThatThrownBy(() -> service.verifyEmail("ve@nammamedmate.test", "000000", "vip", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
    assertThatThrownBy(() -> service.verifyEmail("ve@nammamedmate.test", "000000", "vip2", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
    assertThatThrownBy(() -> service.verifyEmail("ve@nammamedmate.test", "000000", "vip3", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_MAX_ATTEMPTS");

    // lockedAt short-circuit
    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            otps.latest.pharmacyId(),
            otps.latest.email(),
            otps.latest.otpHash(),
            3,
            0,
            otps.latest.expiresAt(),
            null,
            NOW,
            otps.latest.lastSentAt(),
            otps.latest.createdAt());
    assertThatThrownBy(() -> service.verifyEmail("ve@nammamedmate.test", "123456", "vip4", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_MAX_ATTEMPTS");

    // otp points at missing pharmacy
    otps.latest =
        new OtpRecord(
            Ids.newId(),
            Ids.newId(),
            "ghost@nammamedmate.test",
            "h",
            0,
            0,
            NOW.plusSeconds(900),
            null,
            null,
            NOW,
            NOW);
    assertThatThrownBy(() -> service.verifyEmail("ghost@nammamedmate.test", "123456", "vip5", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_FOUND");

    // verify IP rate limit
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("pharmacy:verify:ip:vflood", 10, 60);
    }
    assertThatThrownBy(() -> service.verifyEmail("x@y.com", "1", "vflood", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    // fresh for success then already verified
    pharmacies.byId.clear();
    owners.created.clear();
    otps.latest = null;
    service.register(
        cmd("ok@nammamedmate.test", "+919700000011", "LV2", "29ABCPA1234A2ZK", "AABPP1234F"),
        "vip4");
    service.verifyEmail("ok@nammamedmate.test", MagicRegistrationOtp.CODE, "vip4", "ua");
    assertThatThrownBy(
            () ->
                service.verifyEmail(
                    "ok@nammamedmate.test", MagicRegistrationOtp.CODE, "vip4", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_VERIFIED");

    assertThatThrownBy(() -> service.resendOtp(null, "vip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> service.resendOtp("missing@nammamedmate.test", "vip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_FOUND");
    assertThatThrownBy(() -> service.resendOtp("ok@nammamedmate.test", "vip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_VERIFIED");

    pharmacies.byId.clear();
    owners.created.clear();
    otps.latest = null;
    service.register(
        cmd("rs@nammamedmate.test", "+919700000012", "LV3", "29ABCPA1234A3ZJ", "AABPP1235F"),
        "rsip");
    for (int i = 0; i < 5; i++) {
      clock.advance(java.time.Duration.ofSeconds(61));
      service.resendOtp("rs@nammamedmate.test", "rsip" + i);
    }
    clock.advance(java.time.Duration.ofSeconds(61));
    assertThatThrownBy(() -> service.resendOtp("rs@nammamedmate.test", "rsipx"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RESEND_LIMIT_EXCEEDED");

    assertThatThrownBy(() -> service.registrationStatus(null, "sip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.registrationStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    "sip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.registrationStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, Ids.newId(), TokenScope.FULL, "j"),
                    "sip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.registrationStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, Ids.newId(), TokenScope.FULL, "j"),
                    "sip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    UUID pid = pharmacies.byId.keySet().iterator().next();
    Map<String, Object> st =
        service.registrationStatus(
            new MedmatePrincipal(
                owners.created.get(owners.created.size() - 1).staffId(),
                AuthRole.PHARMACY_STAFF,
                pid,
                TokenScope.FULL,
                "j"),
            "sip");
    assertThat(st.get("profile_completeness_pct")).isEqualTo(20);
  }

  @Test
  void validateFieldEdgesAndRandomOtp() {
    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "A",
                        "Shop",
                        "+919700000020",
                        "short@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1236F1ZX",
                        "L",
                        null,
                        "AABPP1236F",
                        "560001"),
                    "ve1"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000021",
                        "no-at",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1236F2ZW",
                        "L21",
                        null,
                        "AABPP1236F",
                        "560001"),
                    "ve2"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000022",
                        "f@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1236F3ZV",
                        "L22",
                        "123",
                        "AABPP1236F",
                        "560001"),
                    "ve3"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FSSAI");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000023",
                        "c@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", 100.0, 0.0),
                        "29AABPP1236F4ZU",
                        "L23",
                        null,
                        "AABPP1236F",
                        "560001"),
                    "ve4"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COORDINATES");

    Map<String, Object> data =
        randomOtpService.register(
            cmd("rand@example.com", "+919700000024", "LR", "29AABPP1236F5ZT", "AABPP1236F"),
            "randip");
    assertThat(data.get("status")).isEqualTo("PENDING_KYC");
    String randOtp = email.lastOtp;
    assertThat(randOtp).hasSize(6);
    assertThatThrownBy(
            () -> randomOtpService.verifyEmail("rand@example.com", "000000", "randip", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OTP");
    assertThat(
            randomOtpService
                .verifyEmail("rand@example.com", randOtp, "randip2", "ua")
                .get("email_verified"))
        .isEqualTo(true);

    // non-magic with generator present
    service.register(
        cmd("gen@example.com", "+919700000077", "LG", "29AABPP1235F9ZQ", "AABPP1235F"), "genip");
    assertThat(email.lastOtp).isEqualTo("654321");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner",
                        "Shop",
                        "+919700000078",
                        null,
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AACPF1234B1ZG",
                        "LE",
                        null,
                        "AACPF1234B",
                        "560001"),
                    "nullemail"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner",
                        "Shop",
                        "+919700000079",
                        "   ",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AACPF1234B2ZF",
                        "LE2",
                        null,
                        "AACPF1234B",
                        "560001"),
                    "blankemail"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> service.verifyEmail("ok@x.com", null, "ip", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThat(service.humanMessage(null)).isEqualTo("Invalid field");
    assertThat(service.humanMessage("INVALID_PAN")).contains("PAN");
    assertThat(service.humanMessage("INVALID_BUSINESS_TYPE")).contains("business_type");
    assertThat(service.humanMessage("INVALID_STATE")).contains("state");
    assertThat(service.humanMessage("NOPE")).isEqualTo("Invalid field");

    // status rate limit + blank client IP
    for (int i = 0; i < 60; i++) {
      limiter.tryAcquire("pharmacy:status:ip:0.0.0.0", 60, 60);
    }
    UUID statusPid = pharmacies.byId.keySet().iterator().next();
    assertThatThrownBy(
            () ->
                service.registrationStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, statusPid, TokenScope.FULL, "j"),
                    "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThatThrownBy(
            () ->
                service.registrationStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, statusPid, TokenScope.FULL, "j"),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    // staff missing after verify prep
    pharmacies.byId.clear();
    owners.created.clear();
    otps.latest = null;
    service.register(
        cmd("nostaff@nammamedmate.test", "+919700000025", "LNS", "29AABPP1236F6ZS", "AABPP1236F"),
        "ns");
    owners.created.clear();
    assertThatThrownBy(
            () ->
                service.verifyEmail(
                    "nostaff@nammamedmate.test", MagicRegistrationOtp.CODE, "ns", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_FOUND");

    // otp.verifiedAt set while pharmacy not verified
    service.register(
        cmd("vats@nammamedmate.test", "+919700000026", "LVT", "29AABPP1236F7ZR", "AABPP1235F"),
        "vt");
    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            otps.latest.pharmacyId(),
            otps.latest.email(),
            otps.latest.otpHash(),
            0,
            0,
            otps.latest.expiresAt(),
            NOW,
            null,
            otps.latest.lastSentAt(),
            otps.latest.createdAt());
    assertThatThrownBy(
            () ->
                service.verifyEmail(
                    "vats@nammamedmate.test", MagicRegistrationOtp.CODE, "vt", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_VERIFIED");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000027",
                        "pin@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "000001", 12.0, 77.0),
                        "29AABPP1236F8ZQ",
                        "L27",
                        "",
                        "AABPP1234F",
                        "000001"),
                    "pin"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");

    assertThat(
            service
                .register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000028",
                        "lng@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", 12.0, null),
                        "29AACPF1234B1ZG",
                        "L28",
                        "   ",
                        "AACPF1234B",
                        "560001"),
                    "lng")
                .get("status"))
        .isEqualTo("PENDING_KYC");

    // resend rate limit
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("pharmacy:resend:email:rl@nammamedmate.test", 3, 60);
    }
    assertThatThrownBy(() -> service.resendOtp("rl@nammamedmate.test", "rl"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void sha256DigestFailureAndStatusNullBusinessName() throws Exception {
    PharmacyRegistrationService broken =
        new PharmacyRegistrationService(
            pharmacies,
            owners,
            otps,
            sessions,
            pincodes,
            audit,
            email,
            outbox,
            staffEnc,
            otpEnc,
            jwt,
            limiter,
            clock,
            new java.security.SecureRandom(),
            () -> "123456",
            () -> {
              throw new java.security.NoSuchAlgorithmException("x");
            });
    broken.register(
        cmd("sha@nammamedmate.test", "+919700000060", "LSHA", "29AACPF1234B2ZF", "AACPF1234B"),
        "sha");
    assertThatThrownBy(
            () ->
                broken.verifyEmail("sha@nammamedmate.test", MagicRegistrationOtp.CODE, "sha", "ua"))
        .isInstanceOf(IllegalStateException.class);

    UUID id = Ids.newId();
    pharmacies.insert(
        new PharmacyRecord(
            id,
            "FallbackName",
            null,
            "O",
            "+919700000061",
            "bn@nammamedmate.test",
            "h",
            "PHARMACY",
            Map.of(),
            "PENDING_KYC",
            "FREE",
            null,
            "g",
            "d",
            "29",
            null,
            "p",
            new BigDecimal("8.00"),
            null,
            false,
            true,
            true,
            "C",
            "FREE",
            NOW,
            NOW));
    Map<String, Object> st =
        service.registrationStatus(
            new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, id, TokenScope.FULL, "j"),
            "sip");
    assertThat(st.get("business_name")).isEqualTo("FallbackName");

    assertThatThrownBy(() -> service.verifyEmail("  ", "123456", "b", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> service.verifyEmail("a@b.com", "  ", "b", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> service.resendOtp("  ", "b"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    String longEmail = "a".repeat(250) + "@nammamedmate.test";
    assertThatThrownBy(
            () ->
                service.register(
                    cmd(longEmail, "+919700000062", "LL", "29AACPF1234B3ZE", "AACPF1234B"), "le"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "S".repeat(121),
                        "+919700000063",
                        "longbiz@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AACPF1234B1ZG",
                        "LL2",
                        null,
                        "AACPF1234B",
                        "560001"),
                    "lb"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000064",
                        "coord@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand("1", "A", "Bengaluru", "Karnataka", "560001", 91.0, 0.0),
                        "29AABPP1234F7ZT",
                        "LC",
                        null,
                        "AABPP1234F",
                        "560001"),
                    "coord"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COORDINATES");

    // resend when otp exists but pharmacy gone
    otps.latest =
        new OtpRecord(
            Ids.newId(),
            Ids.newId(),
            "gone@nammamedmate.test",
            "h",
            0,
            0,
            NOW.plusSeconds(900),
            null,
            null,
            NOW.minusSeconds(120),
            NOW);
    assertThatThrownBy(() -> service.resendOtp("gone@nammamedmate.test", "gone"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_FOUND");

    // attempts >= max with lockedAt null
    service.register(
        cmd("att@nammamedmate.test", "+919700000070", "LATT", "29AABPP1234F8ZS", "AABPP1234F"),
        "att");
    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            otps.latest.pharmacyId(),
            otps.latest.email(),
            otps.latest.otpHash(),
            3,
            0,
            otps.latest.expiresAt(),
            null,
            null,
            otps.latest.lastSentAt(),
            otps.latest.createdAt());
    assertThatThrownBy(
            () ->
                service.verifyEmail(
                    "att@nammamedmate.test", MagicRegistrationOtp.CODE, "att2", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_MAX_ATTEMPTS");

    // pharmacy already emailVerified
    UUID verifiedId = Ids.newId();
    pharmacies.insert(
        new PharmacyRecord(
            verifiedId,
            "N",
            "N",
            "O",
            "+919700000071",
            "ev@nammamedmate.test",
            "h",
            "PHARMACY",
            Map.of(),
            "PENDING_KYC",
            "FREE",
            null,
            "g2",
            "d2",
            "29",
            null,
            "p2",
            new BigDecimal("8.00"),
            null,
            false,
            true,
            true,
            "C",
            "FREE",
            NOW,
            NOW));
    otps.latest =
        new OtpRecord(
            Ids.newId(),
            verifiedId,
            "ev@nammamedmate.test",
            "h",
            0,
            0,
            NOW.plusSeconds(900),
            null,
            null,
            NOW,
            NOW);
    assertThatThrownBy(
            () ->
                service.verifyEmail("ev@nammamedmate.test", MagicRegistrationOtp.CODE, "ev", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_VERIFIED");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        null,
                        "Shop",
                        "+919700000072",
                        "n@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1234F9ZR",
                        "LN",
                        null,
                        "AABPP1234F",
                        "560001"),
                    "nullname"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "  ",
                        "Shop",
                        "+919700000073",
                        "b@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1235F5ZU",
                        "LB",
                        null,
                        "AABPP1235F",
                        "560001"),
                    "blankname"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELD");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000074",
                        "np@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand("1", "A", "Bengaluru", "Karnataka", null, 12.0, 77.0),
                        "29AABPP1235F6ZT",
                        "LNP",
                        null,
                        "AABPP1235F",
                        null),
                    "nullpin"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000075",
                        "badpan@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        addr(),
                        "29AABPP1235F7ZS",
                        "LBP",
                        null,
                        "AAAXP1234F",
                        "560001"),
                    "badpan"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PAN");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000076",
                        "lng2@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", 0.0, 181.0),
                        "29AABPP1235F8ZR",
                        "LLNG",
                        null,
                        "AABPP1235F",
                        "560001"),
                    "badlng"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COORDINATES");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000082",
                        "latlow@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", -91.0, 0.0),
                        "29ABCPA1234A6ZG",
                        "LLATL",
                        null,
                        "ABCPA1234B",
                        "560001"),
                    "latlow"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COORDINATES");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000083",
                        "lnglow@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", 0.0, -181.0),
                        "29ABCPA1234A7ZF",
                        "LLNGL",
                        null,
                        "ABCPA1234C",
                        "560001"),
                    "lnglow"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COORDINATES");

    assertThat(
            service
                .register(
                    new RegisterCommand(
                        "Owner Name",
                        "Shop Name",
                        "+919700000080",
                        "latonly@nammamedmate.test",
                        "Passw0rd!",
                        "PHARMACY",
                        new AddressCommand(
                            "1", "A", "Bengaluru", "Karnataka", "560001", null, 77.5),
                        "29ABCPA1234A4ZI",
                        "LLAT",
                        null,
                        "ABCPA1234A",
                        "560001"),
                    "latonly")
                .get("status"))
        .isEqualTo("PENDING_KYC");

    // resend with otp.verifiedAt set
    service.register(
        cmd("rov@nammamedmate.test", "+919700000081", "LROV", "29ABCPA1234A5ZH", "AABPP1236F"),
        "rov");
    otps.latest =
        new OtpRecord(
            otps.latest.id(),
            otps.latest.pharmacyId(),
            otps.latest.email(),
            otps.latest.otpHash(),
            0,
            0,
            otps.latest.expiresAt(),
            NOW,
            null,
            NOW.minusSeconds(120),
            otps.latest.createdAt());
    assertThatThrownBy(() -> service.resendOtp("rov@nammamedmate.test", "rov2"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_VERIFIED");
  }

  private static AddressCommand addr() {
    return new AddressCommand("12", "MG", "Bengaluru", "Karnataka", "560001", null, null);
  }

  private static RegisterCommand cmd(
      String email, String phone, String licence, String gstin, String pan) {
    return new RegisterCommand(
        "Owner Name",
        "Sharma Medical",
        phone,
        email,
        "Passw0rd!",
        "PHARMACY",
        addr(),
        gstin,
        licence,
        "12345678901234",
        pan,
        "560001");
  }

  private static PharmacyRecord minimal(
      UUID id, String gstin, String pan, String licence, String phone, String email) {
    return new PharmacyRecord(
        id,
        "N",
        "N",
        "O",
        phone,
        email,
        "h",
        "PHARMACY",
        Map.of(),
        "PENDING_KYC",
        "FREE",
        null,
        gstin,
        licence,
        "29",
        null,
        pan,
        new BigDecimal("8.00"),
        null,
        false,
        false,
        true,
        "Bengaluru",
        "FREE",
        NOW,
        NOW);
  }
}
