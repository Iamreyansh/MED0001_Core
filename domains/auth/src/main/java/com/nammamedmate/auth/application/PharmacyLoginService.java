package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.LoginAuditRecord;
import com.nammamedmate.auth.application.port.out.LoginAuditStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import com.nammamedmate.auth.domain.LoginIdentifiers;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PharmacyLoginService {

  static final int LOGIN_RATE_LIMIT = 10;
  static final int LOGIN_RATE_WINDOW_SECONDS = 60;
  static final int MAX_FAILED_ATTEMPTS = 5;
  static final int FAILURE_WINDOW_MINUTES = 10;
  static final int LOCKOUT_MINUTES = 30;
  static final long ACCESS_TTL_SECONDS = 900L;
  static final long REFRESH_TTL_SECONDS = 604_800L;

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private final PharmacyStaffStore staffStore;
  private final PharmacyAssignmentStore assignmentStore;
  private final PharmacyStore pharmacyStore;
  private final AuthSessionStore sessionStore;
  private final LoginAuditStore auditStore;
  private final PasswordEncoder staffPasswordEncoder;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final DigestFactory digestFactory;

  @Autowired
  public PharmacyLoginService(
      PharmacyStaffStore staffStore,
      PharmacyAssignmentStore assignmentStore,
      PharmacyStore pharmacyStore,
      AuthSessionStore sessionStore,
      LoginAuditStore auditStore,
      @Qualifier("staffPasswordEncoder") PasswordEncoder staffPasswordEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        staffStore,
        assignmentStore,
        pharmacyStore,
        sessionStore,
        auditStore,
        staffPasswordEncoder,
        jwtService,
        rateLimiter,
        clock,
        new SecureRandom(),
        () -> MessageDigest.getInstance("SHA-256"));
  }

  PharmacyLoginService(
      PharmacyStaffStore staffStore,
      PharmacyAssignmentStore assignmentStore,
      PharmacyStore pharmacyStore,
      AuthSessionStore sessionStore,
      LoginAuditStore auditStore,
      PasswordEncoder staffPasswordEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock,
      SecureRandom secureRandom,
      DigestFactory digestFactory) {
    this.staffStore = staffStore;
    this.assignmentStore = assignmentStore;
    this.pharmacyStore = pharmacyStore;
    this.sessionStore = sessionStore;
    this.auditStore = auditStore;
    this.staffPasswordEncoder = staffPasswordEncoder;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.digestFactory = digestFactory;
  }

  public PharmacyLoginResult login(
      String identifier,
      String password,
      UUID requestedPharmacyId,
      String clientIp,
      String userAgent) {
    if (password == null || password.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "identifier and password are required", 400);
    }

    LoginIdentifiers.Normalised normalised;
    try {
      normalised = LoginIdentifiers.normalise(identifier);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Malformed identifier", 400);
    }
    if (normalised == null) {
      throw new AppException("VALIDATION_ERROR", "identifier and password are required", 400);
    }

    String ipKey = "pharmacy:ip:login:" + clientIp + ":count";
    if (!rateLimiter.tryAcquire(ipKey, LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "IP login rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(ipKey, LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW_SECONDS));
    }

    Optional<PharmacyStaffRecord> found =
        normalised.type() == LoginIdentifiers.Type.EMAIL
            ? staffStore.findByEmail(normalised.value())
            : staffStore.findByPhone(normalised.value());

    if (found.isEmpty()) {
      audit(normalised.value(), null, false, "STAFF_NOT_FOUND", clientIp, userAgent);
      throw new AppException("STAFF_NOT_FOUND", "No account with that identifier", 404);
    }
    PharmacyStaffRecord staff = found.get();

    if ("SUSPENDED".equals(staff.status())) {
      audit(normalised.value(), staff.id(), false, "ACCOUNT_SUSPENDED", clientIp, userAgent);
      throw new AppException("ACCOUNT_SUSPENDED", "Account has been suspended", 403);
    }

    Instant now = clock.instant();
    if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
      audit(normalised.value(), staff.id(), false, "ACCOUNT_LOCKED", clientIp, userAgent);
      throw new AppException(
          "ACCOUNT_LOCKED",
          "Account is locked due to too many failed attempts",
          403,
          null,
          Map.of("unlock_at", staff.lockedUntil().toString()));
    }

    if (!staffPasswordEncoder.matches(password, staff.passwordHash())) {
      PharmacyStaffRecord updated = applyFailure(staff, now);
      staffStore.save(updated);
      audit(normalised.value(), staff.id(), false, "INVALID_CREDENTIALS", clientIp, userAgent);
      if (updated.lockedUntil() != null && now.isBefore(updated.lockedUntil())) {
        throw new AppException(
            "ACCOUNT_LOCKED",
            "Account is locked due to too many failed attempts",
            403,
            null,
            Map.of("unlock_at", updated.lockedUntil().toString()));
      }
      throw new AppException("INVALID_CREDENTIALS", "Password does not match", 401);
    }

    // Successful authentication — reset lockout
    PharmacyStaffRecord authenticated =
        new PharmacyStaffRecord(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.phone(),
            staff.passwordHash(),
            staff.posPinHash(),
            staff.status(),
            0,
            null,
            null,
            now,
            staff.invitedBy(),
            staff.createdAt(),
            now);
    staffStore.save(authenticated);
    audit(normalised.value(), staff.id(), true, null, clientIp, userAgent);

    List<PharmacyAssignmentRecord> assignments = assignmentStore.listActiveByStaffId(staff.id());
    PharmacyAssignmentRecord activeAssignment = selectAssignment(assignments, requestedPharmacyId);
    PharmacyRecord activePharmacy =
        pharmacyStore
            .findById(activeAssignment.pharmacyId())
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    AuthRole role =
        "pharmacy_owner".equals(activeAssignment.roleCode())
            ? AuthRole.PHARMACY_OWNER
            : AuthRole.PHARMACY_STAFF;
    String accessToken =
        jwtService.issueAccessToken(
            new JwtClaims(
                staff.id(),
                role,
                activeAssignment.pharmacyId(),
                TokenScope.FULL,
                Ids.newId().toString()),
            ACCESS_TTL_SECONDS);

    String refreshToken = opaqueToken();
    Instant refreshExpires = now.plus(REFRESH_TTL_SECONDS, ChronoUnit.SECONDS);
    sessionStore.save(
        new AuthSessionRecord(
            Ids.newId(),
            staff.id(),
            "pharmacy_staff",
            sha256Hex(refreshToken),
            "full",
            null,
            clientIp == null ? "0.0.0.0" : clientIp,
            userAgent,
            now,
            now,
            refreshExpires,
            activeAssignment.pharmacyId()));

    return new PharmacyLoginResult(
        accessToken,
        refreshToken,
        ACCESS_TTL_SECONDS,
        REFRESH_TTL_SECONDS,
        activePharmacy,
        activeAssignment.roleCode(),
        authenticated,
        assignments);
  }

  private PharmacyAssignmentRecord selectAssignment(
      List<PharmacyAssignmentRecord> assignments, UUID requestedPharmacyId) {
    if (assignments.isEmpty()) {
      throw new AppException("STAFF_NOT_ASSIGNED", "Staff has no active pharmacy assignment", 403);
    }
    if (requestedPharmacyId == null) {
      return assignments.get(0);
    }
    return assignments.stream()
        .filter(a -> requestedPharmacyId.equals(a.pharmacyId()))
        .findFirst()
        .orElseThrow(
            () ->
                new AppException(
                    "FORBIDDEN", "Staff is not assigned to the requested pharmacy", 403));
  }

  private PharmacyStaffRecord applyFailure(PharmacyStaffRecord staff, Instant now) {
    int attempts;
    if (staff.lastFailedAt() == null
        || staff.lastFailedAt().isBefore(now.minus(FAILURE_WINDOW_MINUTES, ChronoUnit.MINUTES))) {
      attempts = 1;
    } else {
      attempts = staff.failedLoginAttempts() + 1;
    }
    Instant lockedUntil =
        attempts >= MAX_FAILED_ATTEMPTS
            ? now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES)
            : staff.lockedUntil();
    return new PharmacyStaffRecord(
        staff.id(),
        staff.name(),
        staff.email(),
        staff.phone(),
        staff.passwordHash(),
        staff.posPinHash(),
        staff.status(),
        attempts,
        lockedUntil,
        now,
        staff.lastLoginAt(),
        staff.invitedBy(),
        staff.createdAt(),
        now);
  }

  private void audit(
      String identifier, UUID staffId, boolean success, String reason, String ip, String ua) {
    auditStore.save(
        new LoginAuditRecord(
            Ids.newId(),
            "pharmacy_staff",
            identifier,
            staffId,
            success,
            reason,
            ip,
            ua,
            clock.instant()));
  }

  private String opaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  String sha256Hex(String value) {
    try {
      MessageDigest digest = digestFactory.create();
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
