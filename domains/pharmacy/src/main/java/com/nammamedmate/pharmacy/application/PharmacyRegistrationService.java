package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore.OwnerCreate;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacySessionStore;
import com.nammamedmate.pharmacy.application.port.out.PincodeReferenceStore;
import com.nammamedmate.pharmacy.application.port.out.RegistrationAuditStore;
import com.nammamedmate.pharmacy.application.port.out.RegistrationEmailSender;
import com.nammamedmate.pharmacy.domain.BusinessTypes;
import com.nammamedmate.pharmacy.domain.Gstin;
import com.nammamedmate.pharmacy.domain.IndianPhone;
import com.nammamedmate.pharmacy.domain.IndianStates;
import com.nammamedmate.pharmacy.domain.MagicRegistrationOtp;
import com.nammamedmate.pharmacy.domain.Pan;
import com.nammamedmate.pharmacy.domain.PharmacyPassword;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyRegistrationService {

  static final int REGISTER_IP_LIMIT = 5;
  static final int REGISTER_IP_WINDOW = 60;
  static final int PHONE_REG_LIMIT = 1;
  static final int PHONE_REG_WINDOW = 86_400;
  static final int VERIFY_IP_LIMIT = 10;
  static final int VERIFY_IP_WINDOW = 60;
  static final int RESEND_EMAIL_LIMIT = 3;
  static final int RESEND_EMAIL_WINDOW = 60;
  static final int STATUS_LIMIT = 60;
  static final int STATUS_WINDOW = 60;
  static final int OTP_TTL_MINUTES = 15;
  static final int OTP_MAX_ATTEMPTS = 3;
  static final int OTP_MAX_RESENDS = 5;
  static final int RESEND_COOLDOWN_SECONDS = 60;
  static final long ACCESS_TTL_SECONDS = 86_400L;
  static final long REFRESH_TTL_SECONDS = 2_592_000L;
  static final int DOCUMENTS_REQUIRED = 5;
  static final int PROFILE_PCT_AFTER_VERIFY = 45;

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final PharmacyRegistrationStore pharmacies;
  private final PharmacyOwnerAccountStore owners;
  private final PharmacyEmailOtpStore otps;
  private final PharmacySessionStore sessions;
  private final PincodeReferenceStore pincodes;
  private final RegistrationAuditStore audit;
  private final RegistrationEmailSender emailSender;
  private final OutboxPublisher outbox;
  private final PasswordEncoder passwordEncoder;
  private final PasswordEncoder otpEncoder;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final Supplier<String> otpGenerator;
  private final DigestFactory digestFactory;

  // Optional: wired by Spring when KycDocumentStore is available; null in unit tests
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  KycDocumentStore kycDocumentStore;

  @Autowired
  public PharmacyRegistrationService(
      PharmacyRegistrationStore pharmacies,
      PharmacyOwnerAccountStore owners,
      PharmacyEmailOtpStore otps,
      PharmacySessionStore sessions,
      PincodeReferenceStore pincodes,
      RegistrationAuditStore audit,
      RegistrationEmailSender emailSender,
      OutboxPublisher outbox,
      @Qualifier("staffPasswordEncoder") PasswordEncoder passwordEncoder,
      @Qualifier("passwordEncoder") PasswordEncoder otpEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        pharmacies,
        owners,
        otps,
        sessions,
        pincodes,
        audit,
        emailSender,
        outbox,
        passwordEncoder,
        otpEncoder,
        jwtService,
        rateLimiter,
        clock,
        new SecureRandom(),
        null,
        () -> MessageDigest.getInstance("SHA-256"));
  }

  PharmacyRegistrationService(
      PharmacyRegistrationStore pharmacies,
      PharmacyOwnerAccountStore owners,
      PharmacyEmailOtpStore otps,
      PharmacySessionStore sessions,
      PincodeReferenceStore pincodes,
      RegistrationAuditStore audit,
      RegistrationEmailSender emailSender,
      OutboxPublisher outbox,
      PasswordEncoder passwordEncoder,
      PasswordEncoder otpEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock,
      SecureRandom secureRandom,
      Supplier<String> otpGenerator) {
    this(
        pharmacies,
        owners,
        otps,
        sessions,
        pincodes,
        audit,
        emailSender,
        outbox,
        passwordEncoder,
        otpEncoder,
        jwtService,
        rateLimiter,
        clock,
        secureRandom,
        otpGenerator,
        () -> MessageDigest.getInstance("SHA-256"));
  }

  PharmacyRegistrationService(
      PharmacyRegistrationStore pharmacies,
      PharmacyOwnerAccountStore owners,
      PharmacyEmailOtpStore otps,
      PharmacySessionStore sessions,
      PincodeReferenceStore pincodes,
      RegistrationAuditStore audit,
      RegistrationEmailSender emailSender,
      OutboxPublisher outbox,
      PasswordEncoder passwordEncoder,
      PasswordEncoder otpEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock,
      SecureRandom secureRandom,
      Supplier<String> otpGenerator,
      DigestFactory digestFactory) {
    this.pharmacies = pharmacies;
    this.owners = owners;
    this.otps = otps;
    this.sessions = sessions;
    this.pincodes = pincodes;
    this.audit = audit;
    this.emailSender = emailSender;
    this.outbox = outbox;
    this.passwordEncoder = passwordEncoder;
    this.otpEncoder = otpEncoder;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.otpGenerator = otpGenerator;
    this.digestFactory = digestFactory;
  }

  @Transactional
  public Map<String, Object> register(RegisterCommand cmd, String clientIp) {
    String ipKey = "pharmacy:register:ip:" + clientIp;
    if (!rateLimiter.tryAcquire(ipKey, REGISTER_IP_LIMIT, REGISTER_IP_WINDOW)) {
      throw limited(
          "RATE_LIMIT_EXCEEDED",
          "Too many registration attempts",
          ipKey,
          REGISTER_IP_LIMIT,
          REGISTER_IP_WINDOW);
    }

    ValidatedRegistration v;
    try {
      v = validate(cmd);
    } catch (AppException ex) {
      auditFailure(
          null,
          cmd == null ? null : cmd.email(),
          cmd == null ? null : cmd.phone(),
          clientIp,
          ex.code());
      throw ex;
    }

    String phoneKey = "pharmacy:register:phone:" + v.phone();
    if (!rateLimiter.tryAcquire(phoneKey, PHONE_REG_LIMIT, PHONE_REG_WINDOW)) {
      throw limited(
          "RATE_LIMIT_EXCEEDED",
          "Registration already attempted for this phone",
          phoneKey,
          PHONE_REG_LIMIT,
          PHONE_REG_WINDOW);
    }

    if (owners.emailTakenPlatformWide(v.email())) {
      auditFailure(null, v.email(), v.phone(), clientIp, "EMAIL_ALREADY_REGISTERED");
      throw new AppException("EMAIL_ALREADY_REGISTERED", "Email already registered", 409);
    }
    if (owners.phoneTakenPlatformWide(v.phone())) {
      auditFailure(null, v.email(), v.phone(), clientIp, "PHONE_ALREADY_REGISTERED");
      throw new AppException("PHONE_ALREADY_REGISTERED", "Phone already registered", 409);
    }
    if (pharmacies.existsDrugLicence(v.drugLicence(), v.stateCode())) {
      auditFailure(null, v.email(), v.phone(), clientIp, "DRUG_LICENCE_ALREADY_REGISTERED");
      throw new AppException(
          "DRUG_LICENCE_ALREADY_REGISTERED", "Drug licence already registered in this state", 409);
    }
    if (pharmacies.existsGstin(v.gstin())) {
      auditFailure(null, v.email(), v.phone(), clientIp, "GSTIN_ALREADY_REGISTERED");
      throw new AppException("GSTIN_ALREADY_REGISTERED", "GSTIN already registered", 409);
    }
    if (pharmacies.existsPan(v.pan())) {
      auditFailure(null, v.email(), v.phone(), clientIp, "PAN_ALREADY_REGISTERED");
      throw new AppException("PAN_ALREADY_REGISTERED", "PAN already registered", 409);
    }

    Instant now = clock.instant();
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    String passwordHash = passwordEncoder.encode(v.password());

    PharmacyRecord pharmacy =
        new PharmacyRecord(
            pharmacyId,
            v.businessName(),
            v.businessName(),
            v.ownerName(),
            v.phone(),
            v.email(),
            passwordHash,
            v.businessType(),
            v.address(),
            "PENDING_KYC",
            "FREE",
            null,
            v.gstin(),
            v.drugLicence(),
            v.stateCode(),
            v.fssai(),
            v.pan(),
            new BigDecimal("8.00"),
            null,
            false,
            false,
            true,
            v.city(),
            "FREE",
            now,
            now,
            null);
    pharmacies.insert(pharmacy);
    owners.createOwner(
        new OwnerCreate(
            staffId,
            v.ownerName(),
            v.email(),
            v.phone(),
            passwordHash,
            pharmacyId,
            PharmacyOwnerAccountStore.OWNER_ROLE_ID,
            now));

    String otp = nextOtp(v.email());
    OtpRecord otpRecord =
        new OtpRecord(
            Ids.newId(),
            pharmacyId,
            v.email(),
            otpEncoder.encode(otp),
            0,
            0,
            now.plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES),
            null,
            null,
            now,
            now);
    otps.insert(otpRecord);
    emailSender.sendOtp(v.email(), otp);
    outbox.publish(
        DomainEvent.of(
            "pharmacy.registration.otp_requested",
            "pharmacy",
            pharmacyId,
            Map.of("pharmacy_id", pharmacyId.toString())));

    audit.save(Ids.newId(), pharmacyId, v.email(), v.phone(), clientIp, "SUCCESS", null, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "PENDING_KYC");
    data.put("plan", "FREE");
    data.put("email_verification_required", true);
    data.put("message", "Registration submitted. Please verify your email to continue.");
    return data;
  }

  @Transactional
  public Map<String, Object> verifyEmail(
      String emailRaw, String otpRaw, String clientIp, String userAgent) {
    String ipKey = "pharmacy:verify:ip:" + clientIp;
    if (!rateLimiter.tryAcquire(ipKey, VERIFY_IP_LIMIT, VERIFY_IP_WINDOW)) {
      throw limited(
          "RATE_LIMIT_EXCEEDED",
          "Too many verify attempts",
          ipKey,
          VERIFY_IP_LIMIT,
          VERIFY_IP_WINDOW);
    }
    if (emailRaw == null || emailRaw.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", "email and otp are required", 400);
    }
    if (otpRaw == null || otpRaw.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", "email and otp are required", 400);
    }
    String email = emailRaw.trim().toLowerCase(Locale.ROOT);
    OtpRecord otp =
        otps.findLatestByEmail(email)
            .orElseThrow(
                () ->
                    new AppException(
                        "EMAIL_NOT_FOUND", "Email not found in pending registrations", 404));
    PharmacyRecord pharmacy =
        pharmacies
            .findById(otp.pharmacyId())
            .orElseThrow(
                () ->
                    new AppException(
                        "EMAIL_NOT_FOUND", "Email not found in pending registrations", 404));

    if (pharmacy.emailVerified()) {
      throw new AppException("EMAIL_ALREADY_VERIFIED", "Email already verified", 409);
    }
    if (otp.verifiedAt() != null) {
      throw new AppException("EMAIL_ALREADY_VERIFIED", "Email already verified", 409);
    }

    Instant now = clock.instant();
    if (otp.lockedAt() != null) {
      throw new AppException("OTP_MAX_ATTEMPTS", "Maximum OTP attempts exceeded", 400);
    }
    if (otp.attempts() >= OTP_MAX_ATTEMPTS) {
      throw new AppException("OTP_MAX_ATTEMPTS", "Maximum OTP attempts exceeded", 400);
    }
    if (now.isAfter(otp.expiresAt())) {
      throw new AppException("OTP_EXPIRED", "OTP has expired", 400);
    }

    boolean ok =
        MagicRegistrationOtp.matches(email, otpRaw) || otpEncoder.matches(otpRaw, otp.otpHash());
    if (!ok) {
      int attempts = otp.attempts() + 1;
      Instant locked = attempts >= OTP_MAX_ATTEMPTS ? now : null;
      otps.update(
          new OtpRecord(
              otp.id(),
              otp.pharmacyId(),
              otp.email(),
              otp.otpHash(),
              attempts,
              otp.resendCount(),
              otp.expiresAt(),
              otp.verifiedAt(),
              locked,
              otp.lastSentAt(),
              otp.createdAt()));
      if (locked != null) {
        throw new AppException("OTP_MAX_ATTEMPTS", "Maximum OTP attempts exceeded", 400);
      }
      throw new AppException("INVALID_OTP", "OTP does not match", 400);
    }

    otps.update(
        new OtpRecord(
            otp.id(),
            otp.pharmacyId(),
            otp.email(),
            otp.otpHash(),
            otp.attempts(),
            otp.resendCount(),
            otp.expiresAt(),
            now,
            otp.lockedAt(),
            otp.lastSentAt(),
            otp.createdAt()));
    pharmacies.markEmailVerified(pharmacy.id(), now);

    UUID staffId =
        owners
            .findStaffIdByEmail(pharmacy.email())
            .orElseThrow(() -> new AppException("EMAIL_NOT_FOUND", "Owner account missing", 404));
    owners.activateOwner(staffId, now);

    String access =
        jwtService.issueAccessToken(
            new JwtClaims(
                staffId,
                AuthRole.PHARMACY_OWNER,
                pharmacy.id(),
                TokenScope.FULL,
                Ids.newId().toString()),
            ACCESS_TTL_SECONDS);
    String refresh = opaqueToken();
    sessions.save(
        Ids.newId(),
        staffId,
        sha256Hex(refresh),
        clientIp,
        userAgent,
        now,
        now.plus(REFRESH_TTL_SECONDS, ChronoUnit.SECONDS),
        pharmacy.id());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacy.id().toString());
    data.put("email_verified", true);
    data.put("access_token", access);
    data.put("refresh_token", refresh);
    data.put("status", pharmacy.status());
    data.put("next_step", "UPLOAD_KYC_DOCUMENTS");
    data.put("message", "Email verified. Please upload your KYC documents to proceed.");
    return data;
  }

  @Transactional
  public Map<String, Object> resendOtp(String emailRaw, String clientIp) {
    if (emailRaw == null || emailRaw.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", "email is required", 400);
    }
    String email = emailRaw.trim().toLowerCase(Locale.ROOT);
    String rateKey = "pharmacy:resend:email:" + email;
    if (!rateLimiter.tryAcquire(rateKey, RESEND_EMAIL_LIMIT, RESEND_EMAIL_WINDOW)) {
      throw limited(
          "RATE_LIMIT_EXCEEDED",
          "Too many resend attempts",
          rateKey,
          RESEND_EMAIL_LIMIT,
          RESEND_EMAIL_WINDOW);
    }

    OtpRecord otp =
        otps.findLatestByEmail(email)
            .orElseThrow(
                () ->
                    new AppException(
                        "EMAIL_NOT_FOUND", "Email not found in pending registrations", 404));
    PharmacyRecord pharmacy =
        pharmacies
            .findById(otp.pharmacyId())
            .orElseThrow(
                () ->
                    new AppException(
                        "EMAIL_NOT_FOUND", "Email not found in pending registrations", 404));
    if (pharmacy.emailVerified()) {
      throw new AppException("EMAIL_ALREADY_VERIFIED", "Already verified", 409);
    }
    if (otp.verifiedAt() != null) {
      throw new AppException("EMAIL_ALREADY_VERIFIED", "Already verified", 409);
    }
    if (otp.resendCount() >= OTP_MAX_RESENDS) {
      throw new AppException("RESEND_LIMIT_EXCEEDED", "Maximum resends reached", 429);
    }
    Instant now = clock.instant();
    long since = ChronoUnit.SECONDS.between(otp.lastSentAt(), now);
    if (since < RESEND_COOLDOWN_SECONDS) {
      throw new AppException(
          "RESEND_TOO_SOON",
          "Please wait before requesting another OTP",
          429,
          (int) (RESEND_COOLDOWN_SECONDS - since));
    }

    String code = nextOtp(email);
    int resends = otp.resendCount() + 1;
    otps.update(
        new OtpRecord(
            otp.id(),
            otp.pharmacyId(),
            otp.email(),
            otpEncoder.encode(code),
            0,
            resends,
            now.plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES),
            null,
            null,
            now,
            otp.createdAt()));
    emailSender.sendOtp(email, code);
    outbox.publish(
        DomainEvent.of(
            "pharmacy.registration.otp_requested",
            "pharmacy",
            pharmacy.id(),
            Map.of("pharmacy_id", pharmacy.id().toString(), "resend", true)));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "New OTP sent to your email.");
    data.put("retry_after_seconds", RESEND_COOLDOWN_SECONDS);
    data.put("resends_remaining", OTP_MAX_RESENDS - resends);
    return data;
  }

  public Map<String, Object> registrationStatus(MedmatePrincipal principal, String clientIp) {
    if (principal == null || principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Missing or invalid JWT", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp;
    String statusKey = "pharmacy:status:ip:" + ip;
    if (!rateLimiter.tryAcquire(statusKey, STATUS_LIMIT, STATUS_WINDOW)) {
      throw limited(
          "RATE_LIMIT_EXCEEDED",
          "Too many status requests",
          statusKey,
          STATUS_LIMIT,
          STATUS_WINDOW);
    }
    PharmacyRecord pharmacy =
        pharmacies
            .findById(principal.pharmacyId())
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "Pharmacy not found", 401));

    int uploaded =
        kycDocumentStore != null
            ? kycDocumentStore.countByPharmacyAndStatuses(
                principal.pharmacyId(), java.util.List.of("UPLOADED", "UNDER_REVIEW", "VERIFIED"))
            : 0;
    int verified =
        kycDocumentStore != null
            ? kycDocumentStore.countByPharmacyAndStatuses(
                principal.pharmacyId(), java.util.List.of("VERIFIED"))
            : 0;
    int rejected =
        kycDocumentStore != null
            ? kycDocumentStore.countByPharmacyAndStatuses(
                principal.pharmacyId(), java.util.List.of("REJECTED"))
            : 0;

    Map<String, Object> kyc = new LinkedHashMap<>();
    kyc.put("documents_uploaded", uploaded);
    kyc.put("documents_required", DOCUMENTS_REQUIRED);
    kyc.put("documents_verified", verified);
    kyc.put("documents_rejected", rejected);
    kyc.put(
        "submitted_at",
        pharmacy.kycSubmittedAt() != null ? pharmacy.kycSubmittedAt().toString() : null);
    kyc.put("reviewed_at", null);
    kyc.put("rejection_reason", null);
    kyc.put("can_reapply", pharmacy.canReapply());
    kyc.put("next_step", pharmacy.emailVerified() ? "UPLOAD_REMAINING_DOCUMENTS" : "VERIFY_EMAIL");

    int completeness = pharmacy.emailVerified() ? PROFILE_PCT_AFTER_VERIFY : 20;

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacy.id().toString());
    data.put(
        "business_name",
        pharmacy.businessName() == null ? pharmacy.name() : pharmacy.businessName());
    data.put("status", pharmacy.status());
    data.put("plan", pharmacy.plan());
    data.put("email_verified", pharmacy.emailVerified());
    data.put("kyc", kyc);
    data.put("profile_completeness_pct", completeness);
    data.put("created_at", pharmacy.createdAt().toString());
    return data;
  }

  private ValidatedRegistration validate(RegisterCommand cmd) {
    if (cmd == null) {
      throw new AppException("MISSING_REQUIRED_FIELD", "Request body required", 400);
    }
    requireText(cmd.ownerName(), "owner_name", 2, 100);
    requireText(cmd.businessName(), "business_name", 2, 120);
    if (cmd.address() == null) {
      throw new AppException("MISSING_REQUIRED_FIELD", "address is required", 400);
    }
    requireText(cmd.address().flat(), "flat", 1, 100);
    requireText(cmd.address().area(), "area", 1, 200);
    requireText(cmd.address().city(), "city", 1, 100);
    requireText(cmd.drugLicenceNumber(), "drug_licence_number", 1, 50);
    if (cmd.email() == null || cmd.email().isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", "email is required", 400);
    }
    if (cmd.email().length() > 255 || !cmd.email().contains("@")) {
      throw new AppException("MISSING_REQUIRED_FIELD", "email is required", 400);
    }

    String phone;
    String gstin;
    String pan;
    String password;
    String businessType;
    String state;
    try {
      phone = IndianPhone.requireValid(cmd.phone());
      password = PharmacyPassword.requireValid(cmd.password());
      businessType = BusinessTypes.requireValid(cmd.businessType());
      state = IndianStates.requireValid(cmd.address().state());
      pan = Pan.requireValid(cmd.panNumber());
      gstin = Gstin.requireValid(cmd.gstin());
    } catch (IllegalArgumentException ex) {
      throw new AppException(ex.getMessage(), humanMessage(ex.getMessage()), 400);
    }

    String rawPin = cmd.address().pincode();
    String pincode = rawPin == null ? null : rawPin.trim();
    if (pincode == null) {
      throw new AppException("INVALID_PINCODE", "Pincode is not a valid Indian pincode", 400);
    }
    if (!pincode.matches("^[1-9][0-9]{5}$")) {
      throw new AppException("INVALID_PINCODE", "Pincode is not a valid Indian pincode", 400);
    }
    pincodes
        .findServiceable(pincode)
        .orElseThrow(
            () ->
                new AppException("INVALID_PINCODE", "Pincode is not a valid Indian pincode", 400));

    String fssai = null;
    if (cmd.fssaiNumber() != null && !cmd.fssaiNumber().isBlank()) {
      fssai = cmd.fssaiNumber().trim();
      if (!fssai.matches("^[0-9]{14}$")) {
        throw new AppException("INVALID_FSSAI", "fssai_number must be 14 digits", 400);
      }
    }

    double lat = cmd.address().latitude() == null ? 12.9716 : cmd.address().latitude();
    double lng = cmd.address().longitude() == null ? 77.5946 : cmd.address().longitude();
    if (lat < -90 || lat > 90) {
      throw new AppException("INVALID_COORDINATES", "latitude must be between -90 and 90", 400);
    }
    if (lng < -180 || lng > 180) {
      throw new AppException("INVALID_COORDINATES", "longitude must be between -180 and 180", 400);
    }

    Map<String, Object> address = new HashMap<>();
    address.put("flat", cmd.address().flat().trim());
    address.put("area", cmd.address().area().trim());
    address.put("city", cmd.address().city().trim());
    address.put("state", state);
    address.put("pincode", pincode);
    address.put("latitude", lat);
    address.put("longitude", lng);

    return new ValidatedRegistration(
        cmd.ownerName().trim(),
        cmd.businessName().trim(),
        phone,
        cmd.email().trim().toLowerCase(Locale.ROOT),
        password,
        businessType,
        address,
        cmd.address().city().trim(),
        gstin,
        Gstin.stateCode(gstin),
        cmd.drugLicenceNumber().trim(),
        fssai,
        pan);
  }

  private void requireText(String value, String field, int min, int max) {
    if (value == null) {
      throw new AppException("MISSING_REQUIRED_FIELD", field + " is required", 400);
    }
    if (value.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELD", field + " is required", 400);
    }
    String t = value.trim();
    if (t.length() < min) {
      throw new AppException("MISSING_REQUIRED_FIELD", field + " length invalid", 400);
    }
    if (t.length() > max) {
      throw new AppException("MISSING_REQUIRED_FIELD", field + " length invalid", 400);
    }
  }

  String humanMessage(String code) {
    return switch (code) {
      case "INVALID_GSTIN" -> "GSTIN fails format or checksum validation";
      case "INVALID_PAN" -> "PAN number fails format validation";
      case "INVALID_PHONE" -> "Phone not in +91XXXXXXXXXX format";
      case "INVALID_PASSWORD_STRENGTH" -> "Password does not meet complexity requirements";
      case "INVALID_BUSINESS_TYPE" ->
          "business_type must be PHARMACY, HOSPITAL, or CLINIC_PHARMACY";
      case "INVALID_STATE" -> "state must be a valid Indian state or UT name";
      case null, default -> "Invalid field";
    };
  }

  private AppException limited(String code, String msg, String key, int limit, int window) {
    return new AppException(code, msg, 429, rateLimiter.secondsUntilAvailable(key, limit, window));
  }

  private void auditFailure(UUID pharmacyId, String email, String phone, String ip, String code) {
    audit.save(Ids.newId(), pharmacyId, email, phone, ip, "FAILURE", code, clock.instant());
  }

  private String nextOtp(String email) {
    if (MagicRegistrationOtp.isMagicEmail(email)) {
      return MagicRegistrationOtp.CODE;
    }
    if (otpGenerator == null) {
      int n = secureRandom.nextInt(1_000_000);
      return String.format("%06d", n);
    }
    return otpGenerator.get();
  }

  private String opaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(digestFactory.create().digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  public record RegisterCommand(
      String ownerName,
      String businessName,
      String phone,
      String email,
      String password,
      String businessType,
      AddressCommand address,
      String gstin,
      String drugLicenceNumber,
      String fssaiNumber,
      String panNumber,
      String pincode) {}

  public record AddressCommand(
      String flat,
      String area,
      String city,
      String state,
      String pincode,
      Double latitude,
      Double longitude) {}

  private record ValidatedRegistration(
      String ownerName,
      String businessName,
      String phone,
      String email,
      String password,
      String businessType,
      Map<String, Object> address,
      String city,
      String gstin,
      String stateCode,
      String drugLicence,
      String fssai,
      String pan) {}
}
