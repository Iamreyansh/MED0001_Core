package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleStore;
import com.nammamedmate.auth.application.port.out.PharmacyStaffDirectoryRow;
import com.nammamedmate.auth.application.port.out.PharmacyStaffDirectoryStore;
import com.nammamedmate.auth.application.port.out.PharmacyStaffInviteRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffInviteStore;
import com.nammamedmate.auth.application.port.out.PharmacyStaffPasswordResetRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffPasswordResetStore;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.domain.LoginIdentifiers;
import com.nammamedmate.auth.domain.PharmacyRoleCodes;
import com.nammamedmate.auth.domain.PharmacyStaffTokens;
import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyStaffService {

  static final int READ_LIMIT = 30;
  static final int WRITE_LIMIT = 20;
  static final int WINDOW_SECONDS = 60;
  static final Duration INVITE_TTL = Duration.ofDays(7);
  static final Duration RESET_TTL = Duration.ofHours(1);
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Pattern PIN = Pattern.compile("^\\d{4}$");
  private static final Pattern UPPER = Pattern.compile("[A-Z]");
  private static final Pattern DIGIT = Pattern.compile("[0-9]");
  private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

  private final PharmacyStaffStore staffStore;
  private final PharmacyStaffDirectoryStore directory;
  private final PharmacyStaffInviteStore invites;
  private final PharmacyStaffPasswordResetStore resets;
  private final PharmacyRoleStore roles;
  private final RbacPermissionService rbac;
  private final AuthSessionStore sessions;
  private final PasswordEncoder passwordEncoder;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PharmacyStaffService(
      PharmacyStaffStore staffStore,
      PharmacyStaffDirectoryStore directory,
      PharmacyStaffInviteStore invites,
      PharmacyStaffPasswordResetStore resets,
      PharmacyRoleStore roles,
      RbacPermissionService rbac,
      AuthSessionStore sessions,
      @Qualifier("staffPasswordEncoder") PasswordEncoder passwordEncoder,
      RateLimiter rateLimiter,
      Clock clock) {
    this.staffStore = staffStore;
    this.directory = directory;
    this.invites = invites;
    this.resets = resets;
    this.roles = roles;
    this.rbac = rbac;
    this.sessions = sessions;
    this.passwordEncoder = passwordEncoder;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public StaffListResult list(
      MedmatePrincipal principal, String status, String search, Integer page, Integer limit) {
    rbac.requirePharmacyAssignment(principal);
    rateLimit(principal, "list", READ_LIMIT);
    PageRequest pageReq = PageRequest.normalize(page, limit, null, "desc");
    String statusFilter =
        status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())
            ? null
            : status.trim().toUpperCase(Locale.ROOT);
    String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    List<PharmacyStaffDirectoryRow> rows = new ArrayList<>();
    for (PharmacyStaffDirectoryRow row : directory.listDirectory(principal.pharmacyId())) {
      if (statusFilter != null && !statusFilter.equalsIgnoreCase(row.status())) {
        continue;
      }
      if (!q.isEmpty() && !matchesSearch(row, q)) {
        continue;
      }
      rows.add(row);
    }
    long total = rows.size();
    int from = Math.min(pageReq.offset(), rows.size());
    int to = Math.min(from + pageReq.limit(), rows.size());
    List<Map<String, Object>> data = new ArrayList<>();
    for (PharmacyStaffDirectoryRow row : rows.subList(from, to)) {
      data.add(toListItem(row));
    }
    return new StaffListResult(data, PaginationMeta.of(pageReq.page(), pageReq.limit(), total));
  }

  @Transactional
  public Map<String, Object> invite(
      MedmatePrincipal principal, String name, String email, String phone, String roleCode) {
    requireManage(principal);
    rateLimit(principal, "invite", WRITE_LIMIT);
    String trimmedName = requireName(name);
    String trimmedEmail = requireEmail(email);
    String trimmedPhone = trimToNull(phone);
    PharmacyRoleRecord role = resolveAssignableRole(principal.pharmacyId(), roleCode);
    Instant now = clock.instant();
    PharmacyStaffRecord existing = staffStore.findByEmail(trimmedEmail).orElse(null);
    UUID staffId;
    if (existing == null) {
      staffId = Ids.newId();
      staffStore.save(
          new PharmacyStaffRecord(
              staffId,
              trimmedName,
              trimmedEmail,
              trimmedPhone,
              passwordEncoder.encode(Ids.newId().toString()),
              null,
              "INVITED",
              0,
              null,
              null,
              null,
              principal.subject(),
              now,
              now));
      directory.insertAssignment(Ids.newId(), staffId, principal.pharmacyId(), role.id(), now);
    } else {
      staffId = existing.id();
      var assignment = directory.findAssignment(staffId, principal.pharmacyId());
      if (assignment.isPresent() && assignment.get().isActive()) {
        throw new AppException(
            "STAFF_ALREADY_ASSIGNED", "Staff already belongs to this pharmacy", 409);
      }
      if (assignment.isPresent()) {
        directory.reactivateAssignment(staffId, principal.pharmacyId(), role.id());
      } else {
        directory.insertAssignment(Ids.newId(), staffId, principal.pharmacyId(), role.id(), now);
      }
      staffStore.save(
          new PharmacyStaffRecord(
              existing.id(),
              trimmedName,
              existing.email(),
              trimmedPhone == null ? existing.phone() : trimmedPhone,
              existing.passwordHash(),
              existing.posPinHash(),
              "INVITED",
              existing.failedLoginAttempts(),
              existing.lockedUntil(),
              existing.lastFailedAt(),
              existing.lastLoginAt(),
              principal.subject(),
              existing.createdAt(),
              now));
    }
    String token = PharmacyStaffTokens.generate();
    Instant expires = now.plus(INVITE_TTL);
    invites.insert(
        new PharmacyStaffInviteRecord(
            Ids.newId(),
            staffId,
            principal.pharmacyId(),
            PharmacyStaffTokens.sha256Hex(token),
            expires,
            null,
            now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staffId.toString());
    data.put("email", trimmedEmail);
    data.put("status", "INVITED");
    data.put("role", role.code());
    data.put("invite_token", token);
    data.put("invite_expires_at", expires.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> completeInvite(String token, String password) {
    if (token == null || token.isBlank()) {
      throw new AppException("INVITE_INVALID", "Invite token is required", 400);
    }
    String rawPassword = requireStrongPassword(password);
    Instant now = clock.instant();
    PharmacyStaffInviteRecord invite =
        invites
            .findActiveByTokenHash(PharmacyStaffTokens.sha256Hex(token.trim()))
            .orElseThrow(
                () -> new AppException("INVITE_INVALID", "Invite is invalid or expired", 404));
    if (invite.expiresAt() != null && invite.expiresAt().isBefore(now)) {
      throw new AppException("INVITE_EXPIRED", "Invite has expired", 410);
    }
    PharmacyStaffRecord staff =
        staffStore
            .findById(invite.staffId())
            .orElseThrow(
                () -> new AppException("INVITE_INVALID", "Invite is invalid or expired", 404));
    staffStore.save(
        new PharmacyStaffRecord(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.phone(),
            passwordEncoder.encode(rawPassword),
            staff.posPinHash(),
            "ACTIVE",
            0,
            null,
            null,
            staff.lastLoginAt(),
            staff.invitedBy(),
            staff.createdAt(),
            now));
    invites.markUsed(invite.id(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staff.id().toString());
    data.put("status", "ACTIVE");
    return data;
  }

  @Transactional
  public Map<String, Object> deactivate(MedmatePrincipal principal, UUID staffId) {
    requireManage(principal);
    rateLimit(principal, "deactivate", WRITE_LIMIT);
    if (staffId == null) {
      throw new AppException("VALIDATION_ERROR", "staff_id is required", 400);
    }
    if (staffId.equals(principal.subject())) {
      throw new AppException(
          "CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account", 409);
    }
    PharmacyAssignmentRecord assignment =
        directory
            .findAssignment(staffId, principal.pharmacyId())
            .orElseThrow(
                () -> new AppException("STAFF_NOT_FOUND", "Staff is not in this pharmacy", 404));
    if (PharmacyRoleCodes.isOwner(assignment.roleCode())
        && countActiveOwners(principal.pharmacyId()) <= 1) {
      throw new AppException("LAST_OWNER", "Cannot deactivate the last pharmacy owner", 409);
    }
    Instant now = clock.instant();
    directory.deactivateAssignment(staffId, principal.pharmacyId(), now);
    staffStore
        .findById(staffId)
        .ifPresent(
            staff ->
                staffStore.save(
                    new PharmacyStaffRecord(
                        staff.id(),
                        staff.name(),
                        staff.email(),
                        staff.phone(),
                        staff.passwordHash(),
                        staff.posPinHash(),
                        "SUSPENDED",
                        staff.failedLoginAttempts(),
                        staff.lockedUntil(),
                        staff.lastFailedAt(),
                        staff.lastLoginAt(),
                        staff.invitedBy(),
                        staff.createdAt(),
                        now)));
    sessions.revokeAllForUser(staffId, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staffId.toString());
    data.put("status", "SUSPENDED");
    return data;
  }

  @Transactional
  public Map<String, Object> setPosPin(MedmatePrincipal principal, UUID staffId, String pin) {
    requireManage(principal);
    rateLimit(principal, "pos-pin", WRITE_LIMIT);
    if (staffId == null) {
      throw new AppException("VALIDATION_ERROR", "staff_id is required", 400);
    }
    if (pin == null || !PIN.matcher(pin).matches()) {
      throw new AppException("VALIDATION_ERROR", "POS PIN must be 4 digits", 400);
    }
    directory
        .findAssignment(staffId, principal.pharmacyId())
        .orElseThrow(
            () -> new AppException("STAFF_NOT_FOUND", "Staff is not in this pharmacy", 404));
    PharmacyStaffRecord staff =
        staffStore
            .findById(staffId)
            .orElseThrow(
                () -> new AppException("STAFF_NOT_FOUND", "Staff is not in this pharmacy", 404));
    Instant now = clock.instant();
    staffStore.save(
        new PharmacyStaffRecord(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.phone(),
            staff.passwordHash(),
            passwordEncoder.encode(pin),
            staff.status(),
            staff.failedLoginAttempts(),
            staff.lockedUntil(),
            staff.lastFailedAt(),
            staff.lastLoginAt(),
            staff.invitedBy(),
            staff.createdAt(),
            now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staffId.toString());
    data.put("pos_pin_set", true);
    return data;
  }

  /**
   * Public forgot-password. Always returns the same payload so callers cannot probe accounts.
   * Tokens are never returned here; email/SMS delivery is fail-closed until MSG91 is live. Owners
   * can issue a one-time token via {@link #issuePasswordReset}.
   */
  @Transactional
  public Map<String, Object> requestPasswordReset(String identifier) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("requested", true);
    LoginIdentifiers.Normalised normalised;
    try {
      normalised = LoginIdentifiers.normalise(identifier);
    } catch (IllegalArgumentException ex) {
      return data;
    }
    if (normalised == null) {
      return data;
    }
    String key = "pharmacy:forgot:" + normalised.value();
    if (!rateLimiter.tryAcquire(key, WRITE_LIMIT, WINDOW_SECONDS)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429, 60);
    }
    PharmacyStaffRecord staff =
        (normalised.type() == LoginIdentifiers.Type.EMAIL
                ? staffStore.findByEmail(normalised.value())
                : staffStore.findByPhone(normalised.value()))
            .orElse(null);
    if (staff == null || !"ACTIVE".equals(staff.status())) {
      return data;
    }
    persistReset(staff.id());
    return data;
  }

  @Transactional
  public Map<String, Object> completePasswordReset(String token, String password) {
    if (token == null || token.isBlank()) {
      throw new AppException("RESET_INVALID", "Reset token is required", 400);
    }
    String rawPassword = requireStrongPassword(password);
    Instant now = clock.instant();
    PharmacyStaffPasswordResetRecord reset =
        resets
            .findActiveByTokenHash(PharmacyStaffTokens.sha256Hex(token.trim()))
            .orElseThrow(
                () -> new AppException("RESET_INVALID", "Reset is invalid or expired", 404));
    if (reset.expiresAt() != null && reset.expiresAt().isBefore(now)) {
      throw new AppException("RESET_EXPIRED", "Reset token has expired", 410);
    }
    PharmacyStaffRecord staff =
        staffStore
            .findById(reset.staffId())
            .orElseThrow(
                () -> new AppException("RESET_INVALID", "Reset is invalid or expired", 404));
    staffStore.save(
        new PharmacyStaffRecord(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.phone(),
            passwordEncoder.encode(rawPassword),
            staff.posPinHash(),
            staff.status(),
            0,
            null,
            null,
            staff.lastLoginAt(),
            staff.invitedBy(),
            staff.createdAt(),
            now));
    resets.markUsed(reset.id(), now);
    sessions.revokeAllForUser(staff.id(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staff.id().toString());
    data.put("status", staff.status());
    return data;
  }

  @Transactional
  public Map<String, Object> issuePasswordReset(MedmatePrincipal principal, UUID staffId) {
    requireManage(principal);
    rateLimit(principal, "reset", WRITE_LIMIT);
    if (staffId == null) {
      throw new AppException("VALIDATION_ERROR", "staff_id is required", 400);
    }
    directory
        .findAssignment(staffId, principal.pharmacyId())
        .orElseThrow(
            () -> new AppException("STAFF_NOT_FOUND", "Staff is not in this pharmacy", 404));
    PharmacyStaffRecord staff =
        staffStore
            .findById(staffId)
            .orElseThrow(
                () -> new AppException("STAFF_NOT_FOUND", "Staff is not in this pharmacy", 404));
    if (!"ACTIVE".equals(staff.status())) {
      throw new AppException("STAFF_NOT_ACTIVE", "Staff must be active to reset a password", 409);
    }
    IssuedReset issued = persistReset(staff.id());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("staff_id", staff.id().toString());
    data.put("reset_token", issued.token());
    data.put("reset_expires_at", issued.expires().toString());
    return data;
  }

  private IssuedReset persistReset(UUID staffId) {
    Instant now = clock.instant();
    String token = PharmacyStaffTokens.generate();
    Instant expires = now.plus(RESET_TTL);
    resets.insert(
        new PharmacyStaffPasswordResetRecord(
            Ids.newId(), staffId, PharmacyStaffTokens.sha256Hex(token), expires, null, now));
    return new IssuedReset(token, expires);
  }

  private record IssuedReset(String token, Instant expires) {}

  private PharmacyRoleRecord resolveAssignableRole(UUID pharmacyId, String roleCode) {
    if (roleCode == null || roleCode.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "role is required", 400);
    }
    String code = roleCode.trim().toLowerCase(Locale.ROOT);
    if (PharmacyRoleCodes.isOwner(code)) {
      throw new AppException("VALIDATION_ERROR", "Cannot invite another owner", 400);
    }
    return roles
        .findSystemByCode(code)
        .or(() -> roles.findActiveByPharmacyAndCode(pharmacyId, code))
        .orElseThrow(() -> new AppException("ROLE_NOT_FOUND", "Unknown pharmacy role", 404));
  }

  private long countActiveOwners(UUID pharmacyId) {
    long count = 0L;
    for (PharmacyStaffDirectoryRow row : directory.listDirectory(pharmacyId)) {
      if (row.active() && PharmacyRoleCodes.isOwner(row.roleCode())) {
        count++;
      }
    }
    return count;
  }

  private void requireManage(MedmatePrincipal principal) {
    rbac.requirePharmacyAssignment(principal);
    if (principal.role() == AuthRole.PHARMACY_OWNER) {
      return;
    }
    rbac.requirePermission(principal, "staff:manage");
  }

  private void rateLimit(MedmatePrincipal principal, String action, int limit) {
    String key = "pharmacy:staff:" + action + ":" + principal.pharmacyId();
    if (!rateLimiter.tryAcquire(key, limit, WINDOW_SECONDS)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, WINDOW_SECONDS);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static boolean matchesSearch(PharmacyStaffDirectoryRow row, String q) {
    return contains(row.name(), q) || contains(row.email(), q) || contains(row.phone(), q);
  }

  private static boolean contains(String value, String q) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(q);
  }

  private static Map<String, Object> toListItem(PharmacyStaffDirectoryRow row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("staff_id", row.staffId() == null ? null : row.staffId().toString());
    item.put("name", row.name());
    item.put("email", row.email());
    item.put("phone", row.phone());
    item.put("status", row.status());
    item.put("role", row.roleCode());
    item.put("is_active", row.active());
    item.put("joined_at", row.joinedAt() == null ? null : row.joinedAt().toString());
    item.put("pos_pin_set", row.posPinSet());
    return item;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    String trimmed = name.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name max 100 characters", 400);
    }
    return trimmed;
  }

  private static String requireEmail(String email) {
    if (email == null || email.isBlank() || !EMAIL.matcher(email.trim()).matches()) {
      throw new AppException("VALIDATION_ERROR", "A valid email is required", 400);
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static String requireStrongPassword(String raw) {
    if (raw == null
        || raw.length() < 8
        || !UPPER.matcher(raw).find()
        || !DIGIT.matcher(raw).find()
        || !SPECIAL.matcher(raw).find()) {
      throw new AppException(
          "WEAK_PASSWORD", "Password must be 8+ characters with upper, digit, and special", 400);
    }
    return raw;
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public record StaffListResult(List<Map<String, Object>> data, PaginationMeta meta) {}
}
