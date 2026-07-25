package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.LoginAuditRecord;
import com.nammamedmate.auth.application.port.out.LoginAuditStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PosPinLoginService {

  static final int PIN_RATE_LIMIT = 10;
  static final int PIN_RATE_WINDOW_SECONDS = 60;
  static final int MAX_FAILED_ATTEMPTS = 5;
  static final int FAILURE_WINDOW_MINUTES = 10;
  static final int LOCKOUT_MINUTES = 30;
  static final long POS_ACCESS_TTL_SECONDS = 14_400L;

  private final PharmacyStaffStore staffStore;
  private final PharmacyAssignmentStore assignmentStore;
  private final PharmacyStore pharmacyStore;
  private final LoginAuditStore auditStore;
  private final PasswordEncoder staffPasswordEncoder;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  @Autowired
  public PosPinLoginService(
      PharmacyStaffStore staffStore,
      PharmacyAssignmentStore assignmentStore,
      PharmacyStore pharmacyStore,
      LoginAuditStore auditStore,
      @Qualifier("staffPasswordEncoder") PasswordEncoder staffPasswordEncoder,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      Clock clock) {
    this.staffStore = staffStore;
    this.assignmentStore = assignmentStore;
    this.pharmacyStore = pharmacyStore;
    this.auditStore = auditStore;
    this.staffPasswordEncoder = staffPasswordEncoder;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public PosPinLoginResult login(
      UUID pharmacyId, UUID staffId, String pin, String clientIp, String userAgent) {
    if (pharmacyId == null || staffId == null || pin == null || !pin.matches("\\d{4}")) {
      throw new AppException(
          "VALIDATION_ERROR", "pharmacy_id, staff_id and 4-digit pin required", 400);
    }

    String ipKey = "pharmacy:ip:pospin:" + clientIp + ":count";
    if (!rateLimiter.tryAcquire(ipKey, PIN_RATE_LIMIT, PIN_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "IP rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(ipKey, PIN_RATE_LIMIT, PIN_RATE_WINDOW_SECONDS));
    }

    PharmacyStaffRecord staff =
        staffStore
            .findById(staffId)
            .orElseThrow(() -> new AppException("STAFF_NOT_FOUND", "Staff not found", 404));

    if ("SUSPENDED".equals(staff.status())) {
      audit(staffId.toString(), staffId, false, "ACCOUNT_SUSPENDED", clientIp, userAgent);
      throw new AppException("ACCOUNT_SUSPENDED", "Account has been suspended", 403);
    }
    if (!"ACTIVE".equals(staff.status())) {
      audit(staffId.toString(), staffId, false, "FORBIDDEN", clientIp, userAgent);
      throw new AppException("FORBIDDEN", "Staff account is not active", 403);
    }

    // Verify pharmacy assignment
    pharmacyStore
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    var assignment =
        assignmentStore
            .findActive(staffId, pharmacyId)
            .orElseThrow(
                () ->
                    new AppException(
                        "STAFF_NOT_ASSIGNED", "Staff is not assigned to this pharmacy", 403));

    Instant now = clock.instant();
    if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
      audit(staffId.toString(), staffId, false, "ACCOUNT_LOCKED", clientIp, userAgent);
      throw new AppException(
          "ACCOUNT_LOCKED",
          "Account is locked due to too many failed PIN attempts",
          403,
          null,
          Map.of("unlock_at", staff.lockedUntil().toString()));
    }

    if (staff.posPinHash() == null) {
      audit(staffId.toString(), staffId, false, "POS_PIN_NOT_SET", clientIp, userAgent);
      throw new AppException("POS_PIN_NOT_SET", "POS PIN has not been configured", 403);
    }

    if (!staffPasswordEncoder.matches(pin, staff.posPinHash())) {
      PharmacyStaffRecord updated = applyPinFailure(staff, now);
      staffStore.save(updated);
      audit(staffId.toString(), staffId, false, "INVALID_PIN", clientIp, userAgent);
      if (updated.lockedUntil() != null && now.isBefore(updated.lockedUntil())) {
        throw new AppException(
            "ACCOUNT_LOCKED",
            "Account is locked due to too many failed PIN attempts",
            403,
            null,
            Map.of("unlock_at", updated.lockedUntil().toString()));
      }
      throw new AppException("INVALID_PIN", "PIN does not match", 401);
    }

    staffStore.save(
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
            now));
    audit(staffId.toString(), staffId, true, null, clientIp, userAgent);
    PharmacyRecord pharmacy = pharmacyStore.findById(pharmacyId).orElseThrow();

    AuthRole role =
        "pharmacy_owner".equals(assignment.roleCode())
            ? AuthRole.PHARMACY_OWNER
            : AuthRole.PHARMACY_STAFF;
    String accessToken =
        jwtService.issueAccessToken(
            new JwtClaims(staffId, role, pharmacyId, TokenScope.POS, Ids.newId().toString()),
            POS_ACCESS_TTL_SECONDS);

    return new PosPinLoginResult(
        accessToken, POS_ACCESS_TTL_SECONDS, staff, assignment.roleCode(), pharmacy);
  }

  private PharmacyStaffRecord applyPinFailure(PharmacyStaffRecord staff, Instant now) {
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
}
