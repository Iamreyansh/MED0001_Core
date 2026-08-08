package com.nammamedmate.settings.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.AdminSessionRevokePort;
import com.nammamedmate.settings.application.port.out.AdminStaffEmailPort;
import com.nammamedmate.settings.application.port.out.AdminStaffStore;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AdminStaffRow;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AuditTrailEntry;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.InviterRef;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.PageResult;
import com.nammamedmate.settings.domain.AdminStaffRoles;
import com.nammamedmate.settings.domain.StaffTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStaffService {

  private static final Pattern EMAIL =
      Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", Pattern.CASE_INSENSITIVE);
  private static final int LIST_LIMIT = 30;
  private static final int INVITE_LIMIT = 10;
  private static final int PATCH_LIMIT = 10;
  private static final int DELETE_LIMIT = 5;
  private static final int RESET_LIMIT = 5;
  private static final int MINUTE = 60;
  private static final int HOUR = 3600;
  private static final int INVITE_TTL_HOURS = 48;
  private static final int RESET_TTL_HOURS = 4;

  private final AdminStaffStore store;
  private final AdminSessionRevokePort sessionRevoke;
  private final AdminAuditAppendPort audit;
  private final AdminStaffEmailPort email;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public AdminStaffService(
      AdminStaffStore store,
      AdminSessionRevokePort sessionRevoke,
      AdminAuditAppendPort audit,
      AdminStaffEmailPort email,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.sessionRevoke = sessionRevoke;
    this.audit = audit;
    this.email = email;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      Integer page,
      Integer limit,
      String role,
      String status,
      String search) {
    requireAnyAdmin(principal);
    rateLimit("admin:staff:list:" + principal.subject(), LIST_LIMIT, MINUTE);

    String roleFilter = blankToNull(role);
    if (roleFilter != null && !AdminStaffRoles.ALL.contains(roleFilter)) {
      throw new AppException("VALIDATION_ERROR", "Invalid role filter", 400);
    }
    String statusFilter = blankToNull(status);
    if (statusFilter != null && !AdminStaffRoles.STATUSES.contains(statusFilter)) {
      throw new AppException("VALIDATION_ERROR", "Invalid status filter", 400);
    }

    PageRequest pageReq = PageRequest.normalize(page, limit, null, "asc");
    PageResult result =
        store.list(roleFilter, statusFilter, blankToNull(search), pageReq.page(), pageReq.limit());
    List<Map<String, Object>> items = new ArrayList<>(result.items().size());
    for (AdminStaffRow row : result.items()) {
      items.add(toListItem(row));
    }
    return new ListResult(
        items, PaginationMeta.of(pageReq.page(), pageReq.limit(), result.total()));
  }

  @Transactional
  public Map<String, Object> invite(
      MedmatePrincipal principal,
      String name,
      String emailAddr,
      String role,
      Boolean sendInviteEmail) {
    requireSuper(principal);
    rateLimit("admin:staff:invite:" + principal.subject(), INVITE_LIMIT, MINUTE);

    String trimmedName = requireName(name);
    String normalisedEmail = requireEmail(emailAddr);
    String inviteRole = blankToNull(role);
    if (inviteRole == null || !AdminStaffRoles.INVITEABLE.contains(inviteRole)) {
      throw new AppException(
          "VALIDATION_ERROR",
          "role must be one of: admin_operations, admin_finance, admin_support, admin_compliance",
          400);
    }
    if (sendInviteEmail == null || !sendInviteEmail) {
      throw new AppException("VALIDATION_ERROR", "send_invite_email must be true", 400);
    }

    Instant now = clock.instant();
    Instant expiresAt = now.plus(INVITE_TTL_HOURS, ChronoUnit.HOURS);
    String token = StaffTokens.generate();
    String tokenHash = StaffTokens.sha256Hex(token);

    Optional<AdminStaffRow> existing = store.findByEmail(normalisedEmail);
    if (existing.isPresent()) {
      AdminStaffRow row = existing.get();
      if (!"INVITED".equals(row.status())) {
        throw new AppException(
            "EMAIL_ALREADY_EXISTS", "Admin account with this email already exists", 409);
      }
      // BR-9: re-send invite for existing INVITED email
      store.refreshInvite(
          row.id(), trimmedName, inviteRole, principal.subject(), tokenHash, expiresAt, now);
      email.sendInvite(row.id(), normalisedEmail, trimmedName, token, expiresAt);
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("invite_expires_at", row.inviteExpiresAt());
      before.put("role", row.role());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("email", normalisedEmail);
      after.put("role", inviteRole);
      after.put("status", "INVITED");
      after.put("invite_expires_at", expiresAt);
      audit.append(
          principal.subject(),
          principal.role().value(),
          row.id(),
          "staff.invite_resent",
          before,
          after);

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("id", row.id());
      data.put("name", trimmedName);
      data.put("email", normalisedEmail);
      data.put("role", inviteRole);
      data.put("status", "INVITED");
      data.put("mfa_enabled", false);
      data.put("invited_by", principal.subject());
      data.put("invite_expires_at", expiresAt);
      data.put("created_at", row.createdAt());
      return data;
    }

    UUID id = Ids.newId();
    store.insertInvited(
        id,
        trimmedName,
        normalisedEmail,
        inviteRole,
        principal.subject(),
        tokenHash,
        expiresAt,
        now);

    email.sendInvite(id, normalisedEmail, trimmedName, token, expiresAt);
    audit.append(
        principal.subject(),
        principal.role().value(),
        id,
        "staff.invited",
        Map.of(),
        Map.of("email", normalisedEmail, "role", inviteRole, "status", "INVITED"));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("name", trimmedName);
    data.put("email", normalisedEmail);
    data.put("role", inviteRole);
    data.put("status", "INVITED");
    data.put("mfa_enabled", false);
    data.put("invited_by", principal.subject());
    data.put("invite_expires_at", expiresAt);
    data.put("created_at", now);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAnyAdmin(principal);
    rateLimit("admin:staff:get:" + principal.subject(), LIST_LIMIT, MINUTE);
    AdminStaffRow row = requireStaff(id);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("name", row.name());
    data.put("email", row.email());
    data.put("role", row.role());
    data.put("status", row.status());
    data.put("mfa_enabled", row.mfaEnabled());
    data.put("last_active_at", row.lastActiveAt());
    data.put("invited_by", invitedByPayload(row.invitedBy()));
    data.put("created_at", row.createdAt());

    List<Map<String, Object>> trail = new ArrayList<>();
    for (AuditTrailEntry e : store.listAuditTrail(id)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("action", e.action());
      entry.put("from", e.from());
      entry.put("to", e.to());
      entry.put("by", e.by());
      entry.put("at", e.at());
      trail.add(entry);
    }
    data.put("audit_trail", trail);
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal, UUID id, String name, String role, String status) {
    requireSuper(principal);
    rateLimit("admin:staff:patch:" + principal.subject(), PATCH_LIMIT, MINUTE);
    AdminStaffRow row = requireStaff(id);

    boolean nameProvided = name != null;
    boolean roleProvided = role != null;
    boolean statusProvided = status != null;
    if (!nameProvided && !roleProvided && !statusProvided) {
      throw new AppException("VALIDATION_ERROR", "At least one field is required", 400);
    }

    String newName = row.name();
    if (nameProvided) {
      newName = requireName(name);
    }

    String newRole = row.role();
    if (roleProvided) {
      String r = blankToNull(role);
      if (r == null || !AdminStaffRoles.ALL.contains(r)) {
        throw new AppException("VALIDATION_ERROR", "Invalid role", 400);
      }
      newRole = r;
    }

    String newStatus = row.status();
    if (statusProvided) {
      String s = blankToNull(status);
      if (s == null || !AdminStaffRoles.PATCH_STATUSES.contains(s)) {
        throw new AppException("VALIDATION_ERROR", "status must be ACTIVE or SUSPENDED", 400);
      }
      newStatus = s;
    }

    boolean roleChanging = !newRole.equals(row.role());
    boolean statusChanging = !newStatus.equals(row.status());
    if ((roleChanging || statusChanging) && principal.subject().equals(id)) {
      throw new AppException("CANNOT_MODIFY_SELF", "Cannot modify own role or status", 422);
    }

    if (wouldRemoveLastSuper(row, newRole, newStatus)) {
      throw new AppException("LAST_SUPER_ADMIN", "Cannot remove the last active admin_super", 422);
    }

    Instant now = clock.instant();
    Map<String, Object> before =
        Map.of("name", row.name(), "role", row.role(), "status", row.status());
    store.update(id, newName, newRole, newStatus, now);

    if (roleChanging) {
      audit.append(
          principal.subject(),
          principal.role().value(),
          id,
          "staff.role_changed",
          Map.of("role", row.role()),
          Map.of("role", newRole));
    }
    if (statusChanging) {
      audit.append(
          principal.subject(),
          principal.role().value(),
          id,
          "staff.status_changed",
          Map.of("status", row.status()),
          Map.of("status", newStatus));
    }
    boolean nameChanged = nameProvided && !newName.equals(row.name());
    if (nameChanged) {
      audit.append(
          principal.subject(),
          principal.role().value(),
          id,
          "staff.name_changed",
          Map.of("name", row.name()),
          Map.of("name", newName));
    }
    if (!roleChanging && !statusChanging && !nameChanged) {
      audit.append(
          principal.subject(),
          principal.role().value(),
          id,
          "staff.updated",
          before,
          Map.of("name", newName, "role", newRole, "status", newStatus));
    }

    if (statusChanging && "SUSPENDED".equals(newStatus)) {
      sessionRevoke.revokeAllSessions(id);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("name", newName);
    data.put("role", newRole);
    data.put("status", newStatus);
    data.put("updated_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id) {
    requireSuper(principal);
    rateLimit("admin:staff:delete:" + principal.subject(), DELETE_LIMIT, MINUTE);
    AdminStaffRow row = requireStaff(id);

    if (principal.subject().equals(id)) {
      throw new AppException("CANNOT_MODIFY_SELF", "Cannot delete yourself", 422);
    }
    if (isLastActiveSuper(row)) {
      throw new AppException("LAST_SUPER_ADMIN", "Cannot remove the last active admin_super", 422);
    }

    Instant now = clock.instant();
    store.softDelete(id, now);
    sessionRevoke.revokeAllSessions(id);
    audit.append(
        principal.subject(),
        principal.role().value(),
        id,
        "staff.deleted",
        Map.of("status", row.status(), "role", row.role()),
        Map.of("deleted_at", now.toString()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("message", "Admin staff member removed. All active sessions have been revoked.");
    return data;
  }

  @Transactional
  public Map<String, Object> resetPassword(MedmatePrincipal principal, UUID id) {
    requireSuper(principal);
    AdminStaffRow row = requireStaff(id);

    String key = "admin:staff:reset:" + id;
    if (!rateLimiter.tryAcquire(key, RESET_LIMIT, HOUR)) {
      int retry = rateLimiter.secondsUntilAvailable(key, RESET_LIMIT, HOUR);
      throw new AppException(
          "RATE_LIMITED",
          "Too many reset emails sent for this staff member",
          429,
          Math.max(retry, 1));
    }

    Instant now = clock.instant();
    Instant expiresAt = now.plus(RESET_TTL_HOURS, ChronoUnit.HOURS);
    String token = StaffTokens.generate();
    store.setResetToken(id, StaffTokens.sha256Hex(token), expiresAt, now);
    email.sendPasswordReset(id, row.email(), row.name(), token, expiresAt);
    audit.append(
        principal.subject(),
        principal.role().value(),
        id,
        "staff.password_reset_sent",
        Map.of(),
        Map.of("reset_link_expires_at", expiresAt.toString()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "Password reset email sent to " + row.email() + ".");
    data.put("reset_link_expires_at", expiresAt);
    return data;
  }

  private Map<String, Object> toListItem(AdminStaffRow row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", row.id());
    item.put("name", row.name());
    item.put("email", row.email());
    item.put("role", row.role());
    item.put("status", row.status());
    item.put("mfa_enabled", row.mfaEnabled());
    item.put("last_active_at", row.lastActiveAt());
    item.put("created_at", row.createdAt());
    return item;
  }

  private Object invitedByPayload(UUID invitedBy) {
    if (invitedBy == null) {
      return null;
    }
    Optional<InviterRef> ref = store.findInviter(invitedBy);
    if (ref.isEmpty()) {
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("id", invitedBy);
      fallback.put("name", null);
      return fallback;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", ref.get().id());
    payload.put("name", ref.get().name());
    return payload;
  }

  private AdminStaffRow requireStaff(UUID id) {
    if (id == null) {
      throw new AppException("STAFF_NOT_FOUND", "No staff with given ID", 404);
    }
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("STAFF_NOT_FOUND", "No staff with given ID", 404));
  }

  private boolean isLastActiveSuper(AdminStaffRow row) {
    if (!AdminStaffRoles.SUPER.equals(row.role()) || !"ACTIVE".equals(row.status())) {
      return false;
    }
    return store.countActiveSuperAdmins() <= 1;
  }

  /** True when demoting or suspending the sole active admin_super. */
  private boolean wouldRemoveLastSuper(AdminStaffRow row, String newRole, String newStatus) {
    if (!isLastActiveSuper(row)) {
      return false;
    }
    return !AdminStaffRoles.SUPER.equals(newRole) || "SUSPENDED".equals(newStatus);
  }

  private void requireAnyAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (!isAdmin(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private void requireSuper(MedmatePrincipal principal) {
    requireAnyAdmin(principal);
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can perform this action", 403);
    }
  }

  private static boolean isAdmin(AuthRole role) {
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static String requireName(String name) {
    String trimmed = name == null ? null : name.trim();
    if (trimmed == null || trimmed.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name max length is 100", 400);
    }
    return trimmed;
  }

  private static String requireEmail(String email) {
    String normalised = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    if (normalised == null || normalised.isEmpty() || !EMAIL.matcher(normalised).matches()) {
      throw new AppException("VALIDATION_ERROR", "Valid email is required", 400);
    }
    return normalised;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
