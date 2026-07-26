package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.AddressCommand;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.RegisterCommand;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore.OwnerCreate;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacySessionStore;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore.PincodeRecord;
import com.nammamedmate.pharmacy.application.port.out.RegistrationAuditStore;
import com.nammamedmate.pharmacy.application.port.out.RegistrationEmailSender;
import com.nammamedmate.pharmacy.domain.MagicRegistrationOtp;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PharmacyRegistrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
  private static final String GSTIN = "29AABPP1234F1ZZ";
  private static final String PAN = "AABPP1234F";

  private final PasswordEncoder staffEnc = new BCryptPasswordEncoder(12);
  private final PasswordEncoder otpEnc = new BCryptPasswordEncoder(10);
  private MutableClock clock;
  private FakePharmacies pharmacies;
  private FakeOwners owners;
  private FakeOtps otps;
  private FakeSessions sessions;
  private FakePincodes pincodes;
  private FakeAudit audit;
  private RecordingEmail email;
  private InMemoryRateLimiter limiter;
  private Rs256JwtService jwt;
  private OutboxPublisher outbox;
  private PharmacyRegistrationService service;

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

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
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
  }

  @Test
  void registerHappyPath() {
    Map<String, Object> data =
        service.register(
            validCmd("owner1@nammamedmate.test", "+919811100001", "DL-1", GSTIN, PAN), "1.1.1.1");
    assertThat(data.get("status")).isEqualTo("PENDING_KYC");
    assertThat(data.get("plan")).isEqualTo("FREE");
    assertThat(data.get("email_verification_required")).isEqualTo(true);
    assertThat(pharmacies.byId).hasSize(1);
    assertThat(owners.created).hasSize(1);
    assertThat(otps.latest).isNotNull();
    assertThat(email.lastOtp).isEqualTo(MagicRegistrationOtp.CODE);
    assertThat(audit.outcomes).contains("SUCCESS");
  }

  @Test
  void registerRejectsInvalidGstinWithoutPersist() {
    RegisterCommand cmd =
        new RegisterCommand(
            "Owner",
            "Shop",
            "+919811100002",
            "a@nammamedmate.test",
            "Passw0rd!",
            "PHARMACY",
            addr(),
            "29AABPP1234F1ZA",
            "DL-2",
            null,
            PAN,
            "560001");
    assertThatThrownBy(() -> service.register(cmd, "1.1.1.1"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GSTIN");
    assertThat(pharmacies.byId).isEmpty();
  }

  @Test
  void registerRejectsEmailConflictAndBadPhoneAndDrugLicence() {
    service.register(
        validCmd("dup@nammamedmate.test", "+919811100003", "DL-3", GSTIN, PAN), "2.2.2.2");
    assertThatThrownBy(
            () ->
                service.register(
                    validCmd(
                        "dup@nammamedmate.test",
                        "+919811100004",
                        "DL-4",
                        "29AABPP1234F2ZY",
                        "AABPP1234F"),
                    "2.2.2.3"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_REGISTERED");

    assertThatThrownBy(
            () ->
                service.register(
                    validCmd(
                        "x@nammamedmate.test",
                        "+911811100005",
                        "DL-5",
                        "29AABPP1234F3ZX",
                        "AABPP1234F"),
                    "2.2.2.4"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE");

    assertThatThrownBy(
            () ->
                service.register(
                    validCmd(
                        "y@nammamedmate.test",
                        "+919811100006",
                        "DL-3",
                        "29AABPP1234F4ZW",
                        "AABPP1235F"),
                    "2.2.2.5"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DRUG_LICENCE_ALREADY_REGISTERED");
  }

  @Test
  void verifyEmailIssuesJwtAndStatusShowsKyc() {
    Map<String, Object> reg =
        service.register(
            validCmd(
                "v@nammamedmate.test", "+919811100007", "DL-7", "29AABPP1234F5ZV", "AABPP1235F"),
            "3.3.3.3");
    String pharmacyId = String.valueOf(reg.get("pharmacy_id"));

    Map<String, Object> verified =
        service.verifyEmail("v@nammamedmate.test", MagicRegistrationOtp.CODE, "3.3.3.3", "ua");
    assertThat(verified.get("email_verified")).isEqualTo(true);
    assertThat(verified.get("access_token")).isInstanceOf(String.class);
    assertThat(verified.get("next_step")).isEqualTo("UPLOAD_KYC_DOCUMENTS");
    assertThat(sessions.saved).isTrue();
    assertThat(owners.activated).contains(owners.created.get(0).staffId());

    MedmatePrincipal principal =
        new MedmatePrincipal(
            owners.created.get(0).staffId(),
            AuthRole.PHARMACY_OWNER,
            UUID.fromString(pharmacyId),
            TokenScope.FULL,
            "jti");
    Map<String, Object> status = service.registrationStatus(principal, "3.3.3.3");
    assertThat(status.get("profile_completeness_pct")).isEqualTo(45);
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) status.get("kyc");
    assertThat(kyc.get("documents_required")).isEqualTo(5);
    assertThat(kyc.get("next_step")).isEqualTo("UPLOAD_REMAINING_DOCUMENTS");
  }

  @Test
  void verifyEmailExpiredAndResendCooldown() {
    service.register(
        validCmd("e@nammamedmate.test", "+919811100008", "DL-8", "29AABPP1234F6ZU", "AABPP1236F"),
        "4.4.4.4");
    clock.advance(DurationMinutes(20));
    assertThatThrownBy(
            () ->
                service.verifyEmail(
                    "e@nammamedmate.test", MagicRegistrationOtp.CODE, "4.4.4.4", "ua"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OTP_EXPIRED");

    clock.set(NOW);
    service.register(
        validCmd("r@nammamedmate.test", "+919811100009", "DL-9", "29AABPP1235F1ZY", "ABCPA1234A"),
        "5.5.5.5");
    assertThatThrownBy(() -> service.resendOtp("r@nammamedmate.test", "5.5.5.5"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RESEND_TOO_SOON");

    clock.advance(DurationMinutes(2));
    Map<String, Object> resent = service.resendOtp("r@nammamedmate.test", "5.5.5.5");
    assertThat(resent.get("resends_remaining")).isEqualTo(4);
  }

  @Test
  void invalidPincodeAndPassword() {
    RegisterCommand badPin =
        new RegisterCommand(
            "Owner",
            "Shop",
            "+919811100010",
            "p@nammamedmate.test",
            "Passw0rd!",
            "PHARMACY",
            new AddressCommand("1", "Area", "Bengaluru", "Karnataka", "999999", null, null),
            "29AABPP1235F2ZX",
            "DL-10",
            null,
            "AACPF1234B",
            "999999");
    assertThatThrownBy(() -> service.register(badPin, "6.6.6.6"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PINCODE");

    RegisterCommand badPw =
        new RegisterCommand(
            "Owner",
            "Shop",
            "+919811100011",
            "pw@nammamedmate.test",
            "weak",
            "PHARMACY",
            addr(),
            "29AABPP1235F3ZW",
            "DL-11",
            null,
            "AACPF1234B",
            "560001");
    assertThatThrownBy(() -> service.register(badPw, "6.6.6.7"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PASSWORD_STRENGTH");
  }

  @Test
  void autowiredConstructorCoverage() {
    PharmacyRegistrationService wired =
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
            clock);
    wired.register(
        validCmd(
            "wired@nammamedmate.test", "+919811100099", "DL-W", "29AABPP1235F4ZV", "AABPP1235F"),
        "wired-ip");
    assertThat(
            wired
                .verifyEmail(
                    "wired@nammamedmate.test", MagicRegistrationOtp.CODE, "wired-ip2", "ua")
                .get("email_verified"))
        .isEqualTo(true);
  }

  private static java.time.Duration DurationMinutes(int m) {
    return java.time.Duration.ofMinutes(m);
  }

  private RegisterCommand validCmd(
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
        null,
        pan,
        "560001");
  }

  private AddressCommand addr() {
    return new AddressCommand("12", "MG Road", "Bengaluru", "Karnataka", "560001", 12.97, 77.59);
  }

  static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    void advance(java.time.Duration d) {
      instant = instant.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  static final class FakePharmacies implements PharmacyRegistrationStore {
    final Map<UUID, PharmacyRecord> byId = new HashMap<>();

    @Override
    public void insert(PharmacyRecord pharmacy) {
      byId.put(pharmacy.id(), pharmacy);
    }

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<PharmacyRecord> findByEmail(String email) {
      return byId.values().stream().filter(p -> email.equals(p.email())).findFirst();
    }

    @Override
    public boolean existsGstin(String gstin) {
      return byId.values().stream().anyMatch(p -> gstin.equals(p.gstin()));
    }

    @Override
    public boolean existsPan(String pan) {
      return byId.values().stream().anyMatch(p -> pan.equals(p.panNumber()));
    }

    @Override
    public boolean existsDrugLicence(String licence, String stateCode) {
      return byId.values().stream()
          .anyMatch(
              p -> licence.equals(p.drugLicenceNumber()) && stateCode.equals(p.licenceStateCode()));
    }

    @Override
    public boolean existsPhone(String phone) {
      return byId.values().stream().anyMatch(p -> phone.equals(p.phone()));
    }

    @Override
    public boolean existsEmail(String email) {
      return byId.values().stream().anyMatch(p -> email.equals(p.email()));
    }

    @Override
    public void markEmailVerified(UUID pharmacyId, Instant at) {
      PharmacyRecord p = byId.get(pharmacyId);
      byId.put(
          pharmacyId,
          new PharmacyRecord(
              p.id(),
              p.name(),
              p.businessName(),
              p.ownerName(),
              p.phone(),
              p.email(),
              p.passwordHash(),
              p.businessType(),
              p.address(),
              p.status(),
              p.plan(),
              p.planExpiresAt(),
              p.gstin(),
              p.drugLicenceNumber(),
              p.licenceStateCode(),
              p.fssaiNumber(),
              p.panNumber(),
              p.commissionPct(),
              p.zoneId(),
              p.online(),
              true,
              p.canReapply(),
              p.city(),
              p.subscriptionPlan(),
              p.createdAt(),
              at));
    }
  }

  static final class FakeOwners implements PharmacyOwnerAccountStore {
    final List<OwnerCreate> created = new ArrayList<>();
    final List<UUID> activated = new ArrayList<>();

    @Override
    public void createOwner(OwnerCreate cmd) {
      created.add(cmd);
    }

    @Override
    public void activateOwner(UUID staffId, Instant now) {
      activated.add(staffId);
    }

    @Override
    public Optional<UUID> findStaffIdByEmail(String email) {
      return created.stream()
          .filter(c -> c.email().equalsIgnoreCase(email))
          .map(OwnerCreate::staffId)
          .findFirst();
    }

    @Override
    public boolean emailTakenPlatformWide(String email) {
      return created.stream().anyMatch(c -> c.email().equalsIgnoreCase(email));
    }

    @Override
    public boolean phoneTakenPlatformWide(String phone) {
      return created.stream().anyMatch(c -> c.phone().equals(phone));
    }
  }

  static final class FakeOtps implements PharmacyEmailOtpStore {
    OtpRecord latest;

    @Override
    public void insert(OtpRecord record) {
      latest = record;
    }

    @Override
    public void update(OtpRecord record) {
      latest = record;
    }

    @Override
    public Optional<OtpRecord> findLatestByEmail(String email) {
      return latest != null && latest.email().equals(email)
          ? Optional.of(latest)
          : Optional.empty();
    }
  }

  static final class FakeSessions implements PharmacySessionStore {
    boolean saved;

    @Override
    public void save(
        UUID sessionId,
        UUID userId,
        String refreshTokenHash,
        String clientIp,
        String userAgent,
        Instant now,
        Instant expiresAt,
        UUID pharmacyId) {
      saved = true;
    }
  }

  static final class FakePincodes implements PincodeReferenceStore {
    @Override
    public Optional<PincodeRecord> findServiceable(String pincode) {
      if ("560001".equals(pincode)) {
        return Optional.of(new PincodeRecord("560001", "29", "Karnataka", true));
      }
      return Optional.empty();
    }
  }

  static final class FakeAudit implements RegistrationAuditStore {
    final List<String> outcomes = new ArrayList<>();

    @Override
    public void save(
        UUID id,
        UUID pharmacyId,
        String email,
        String phone,
        String ip,
        String outcome,
        String errorCode,
        Instant at) {
      outcomes.add(outcome);
    }
  }

  static final class RecordingEmail implements RegistrationEmailSender {
    String lastOtp;

    @Override
    public void sendOtp(String email, String otp) {
      lastOtp = otp;
    }
  }
}
