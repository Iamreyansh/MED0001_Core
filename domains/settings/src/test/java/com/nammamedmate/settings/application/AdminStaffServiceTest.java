package com.nammamedmate.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.AdminStaffService.ListResult;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.AdminSessionRevokePort;
import com.nammamedmate.settings.application.port.out.AdminStaffEmailPort;
import com.nammamedmate.settings.application.port.out.AdminStaffStore;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AdminStaffRow;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AuditTrailEntry;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.InviterRef;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminStaffServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  private FakeStore store;
  private RecordingRevoke revoke;
  private RecordingAudit audit;
  private RecordingEmail email;
  private InMemoryRateLimiter rateLimiter;
  private AdminStaffService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;
  private UUID superId;
  private UUID opsId;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    revoke = new RecordingRevoke();
    audit = new RecordingAudit();
    email = new RecordingEmail();
    rateLimiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new AdminStaffService(
            store, revoke, audit, email, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    superId = Ids.newId();
    opsId = Ids.newId();
    superAdmin = principal(superId, AuthRole.ADMIN_SUPER);
    opsAdmin = principal(opsId, AuthRole.ADMIN_OPERATIONS);
    store.put(row(superId, "Super", "super@test.in", "admin_super", "ACTIVE", null, null));
    store.put(row(opsId, "Ops", "ops@test.in", "admin_operations", "ACTIVE", superId, null));
  }

  @Test
  void ac_inviteCreatesInvitedWith48hExpiryAndEmail() {
    Map<String, Object> data =
        service.invite(superAdmin, "Meera Krishnan", "meera@test.in", "admin_support", true);
    assertThat(data.get("status")).isEqualTo("INVITED");
    assertThat(data.get("invite_expires_at")).isEqualTo(NOW.plus(48, ChronoUnit.HOURS));
    assertThat(data.get("invited_by")).isEqualTo(superId);
    assertThat(email.invites).hasSize(1);
    assertThat(audit.actions).contains("staff.invited");
  }

  @Test
  void ac_opsCannotInvite() {
    assertThatThrownBy(() -> service.invite(opsAdmin, "X", "x@test.in", "admin_support", true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void br9_resendInviteRefreshesExpiryForInvitedEmail() {
    UUID invitedId = Ids.newId();
    Instant oldExpiry = NOW.minus(1, ChronoUnit.HOURS);
    store.put(
        row(
            invitedId,
            "Old Name",
            "meera@test.in",
            "admin_support",
            "INVITED",
            superId,
            oldExpiry));
    Map<String, Object> data =
        service.invite(superAdmin, "Meera Krishnan", "meera@test.in", "admin_finance", true);
    assertThat(data.get("id")).isEqualTo(invitedId);
    assertThat(data.get("name")).isEqualTo("Meera Krishnan");
    assertThat(data.get("role")).isEqualTo("admin_finance");
    assertThat(data.get("invite_expires_at")).isEqualTo(NOW.plus(48, ChronoUnit.HOURS));
    assertThat(data.get("created_at")).isEqualTo(NOW);
    assertThat(email.invites).containsExactly("meera@test.in");
    assertThat(audit.actions).contains("staff.invite_resent");
    assertThat(store.findById(invitedId))
        .get()
        .extracting(AdminStaffRow::inviteExpiresAt)
        .isEqualTo(NOW.plus(48, ChronoUnit.HOURS));
  }

  @Test
  void ac_lastSuperAdminDeleteRejected() {
    assertThatThrownBy(() -> service.delete(superAdmin, superId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_MODIFY_SELF");

    UUID otherSuper = Ids.newId();
    // only one active super — cannot delete them via another identity... use ops? ops forbidden.
    // Create second super then demote first? Delete the only super using a second super.
    store.put(row(otherSuper, "S2", "s2@test.in", "admin_super", "ACTIVE", null, null));
    MedmatePrincipal s2 = principal(otherSuper, AuthRole.ADMIN_SUPER);
    // now two supers — delete one is ok; delete last fails
    service.delete(s2, superId);
    assertThatThrownBy(() -> service.delete(s2, otherSuper))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_MODIFY_SELF");

    // recreate single-super scenario for LAST_SUPER_ADMIN via suspend
    UUID only = Ids.newId();
    UUID actor = Ids.newId();
    store.clear();
    store.put(row(only, "Only", "only@test.in", "admin_super", "ACTIVE", null, null));
    store.put(row(actor, "Actor", "actor@test.in", "admin_super", "ACTIVE", null, null));
    // delete actor first leaving only
    service.delete(principal(only, AuthRole.ADMIN_SUPER), actor);
    assertThatThrownBy(() -> service.delete(principal(Ids.newId(), AuthRole.ADMIN_SUPER), only))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LAST_SUPER_ADMIN");
  }

  @Test
  void ac_cannotDeleteSelf() {
    UUID second = Ids.newId();
    store.put(row(second, "S2", "s2@test.in", "admin_super", "ACTIVE", null, null));
    assertThatThrownBy(() -> service.delete(superAdmin, superId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_MODIFY_SELF");
  }

  @Test
  void ac_suspendRevokesSessions() {
    Map<String, Object> updated = service.update(superAdmin, opsId, null, null, "SUSPENDED");
    assertThat(updated.get("status")).isEqualTo("SUSPENDED");
    assertThat(revoke.revoked).contains(opsId);
    assertThat(audit.actions).contains("staff.status_changed");
  }

  @Test
  void ac_listFiltersByRole() {
    UUID finance = Ids.newId();
    store.put(row(finance, "Fin", "fin@test.in", "admin_finance", "ACTIVE", superId, null));
    var result = service.list(opsAdmin, 1, 20, "admin_finance", null, null);
    assertThat(result.data()).hasSize(1);
    assertThat(result.data().get(0).get("role")).isEqualTo("admin_finance");
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void ac_resetPassword4hAndRateLimitedOnSixth() {
    Map<String, Object> first = service.resetPassword(superAdmin, opsId);
    assertThat(first.get("reset_link_expires_at")).isEqualTo(NOW.plus(4, ChronoUnit.HOURS));
    assertThat(email.resets).hasSize(1);
    for (int i = 0; i < 4; i++) {
      service.resetPassword(superAdmin, opsId);
    }
    assertThatThrownBy(() -> service.resetPassword(superAdmin, opsId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void inviteValidationAndDuplicateEmail() {
    assertThatThrownBy(() -> service.invite(superAdmin, "", "a@b.co", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "bad", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "n@test.in", "admin_super", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "n@test.in", "admin_support", false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "ops@test.in", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_ALREADY_EXISTS");
    assertThatThrownBy(
            () -> service.invite(superAdmin, "x".repeat(101), "ok@test.in", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void getDetailWithInvitedByAndAuditTrail() {
    store.trail.put(
        opsId,
        List.of(
            new AuditTrailEntry(
                "staff.role_changed",
                "admin_support",
                "admin_operations",
                "Super",
                NOW.minusSeconds(60))));
    Map<String, Object> detail = service.get(opsAdmin, opsId);
    assertThat(detail.get("email")).isEqualTo("ops@test.in");
    @SuppressWarnings("unchecked")
    Map<String, Object> invitedBy = (Map<String, Object>) detail.get("invited_by");
    assertThat(invitedBy.get("id")).isEqualTo(superId);
    assertThat(invitedBy.get("name")).isEqualTo("Super");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> trail = (List<Map<String, Object>>) detail.get("audit_trail");
    assertThat(trail).hasSize(1);
  }

  @Test
  void getStaffNotFoundAndMissingInviter() {
    assertThatThrownBy(() -> service.get(opsAdmin, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STAFF_NOT_FOUND");
    UUID orphan = Ids.newId();
    UUID missingInviter = Ids.newId();
    store.put(
        row(orphan, "Orphan", "orphan@test.in", "admin_support", "INVITED", missingInviter, null));
    Map<String, Object> detail = service.get(opsAdmin, orphan);
    @SuppressWarnings("unchecked")
    Map<String, Object> invitedBy = (Map<String, Object>) detail.get("invited_by");
    assertThat(invitedBy.get("id")).isEqualTo(missingInviter);
    assertThat(invitedBy.get("name")).isNull();
  }

  @Test
  void patchLastSuperAdminAndSelfGuards() {
    assertThatThrownBy(() -> service.update(superAdmin, superId, null, "admin_operations", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_MODIFY_SELF");
    assertThatThrownBy(() -> service.update(superAdmin, superId, null, null, "SUSPENDED"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_MODIFY_SELF");

    UUID only = Ids.newId();
    UUID actor = Ids.newId();
    store.clear();
    store.put(row(only, "Only", "only@test.in", "admin_super", "ACTIVE", null, null));
    store.put(row(actor, "Actor", "actor@test.in", "admin_super", "SUSPENDED", null, null));
    MedmatePrincipal actorP = principal(actor, AuthRole.ADMIN_SUPER);
    assertThatThrownBy(() -> service.update(actorP, only, null, "admin_operations", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LAST_SUPER_ADMIN");
    assertThatThrownBy(() -> service.update(actorP, only, null, null, "SUSPENDED"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LAST_SUPER_ADMIN");
  }

  @Test
  void patchNameRoleValidationAndElevate() {
    assertThatThrownBy(() -> service.update(superAdmin, opsId, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, opsId, null, "nope", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, opsId, null, null, "INVITED"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> elevated =
        service.update(superAdmin, opsId, "Ops Updated", "admin_super", "ACTIVE");
    assertThat(elevated.get("role")).isEqualTo("admin_super");
    assertThat(elevated.get("name")).isEqualTo("Ops Updated");
    assertThat(audit.actions).contains("staff.role_changed", "staff.name_changed");

    // name-only self update allowed
    Map<String, Object> selfName = service.update(superAdmin, superId, "Super Renamed", null, null);
    assertThat(selfName.get("name")).isEqualTo("Super Renamed");
  }

  @Test
  void listValidationUnauthorizedForbiddenFilters() {
    assertThatThrownBy(() -> service.list(null, 1, 20, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, 1, 20, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(opsAdmin, 1, 20, "bad", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(opsAdmin, 1, 20, null, "bad", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    var filtered = service.list(opsAdmin, 1, 20, null, "ACTIVE", "ops");
    assertThat(filtered.data()).isNotEmpty();
  }

  @Test
  void deleteRevokesAndStaffNotFoundOnReset() {
    UUID second = Ids.newId();
    store.put(row(second, "S2", "s2@test.in", "admin_super", "ACTIVE", null, null));
    Map<String, Object> deleted = service.delete(principal(second, AuthRole.ADMIN_SUPER), opsId);
    assertThat(deleted.get("message").toString()).contains("revoked");
    assertThat(revoke.revoked).contains(opsId);
    assertThatThrownBy(() -> service.resetPassword(superAdmin, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void getNullInvitedBy() {
    Map<String, Object> detail = service.get(opsAdmin, superId);
    assertThat(detail.get("invited_by")).isNull();
  }

  @Test
  void patchSameValuesTriggersUpdatedAudit() {
    service.update(superAdmin, opsId, "Ops", "admin_operations", "ACTIVE");
    assertThat(audit.actions).contains("staff.updated");
  }

  @Test
  void requireStaffNullId() {
    assertThatThrownBy(() -> service.get(opsAdmin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void inviteNullRoleAndNullSendFlag() {
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "n2@test.in", null, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "n2@test.in", "admin_support", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void patchBlankRoleStatusAndReactivate() {
    assertThatThrownBy(() -> service.update(superAdmin, opsId, null, "   ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, opsId, null, null, "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    store.put(row(opsId, "Ops", "ops@test.in", "admin_operations", "SUSPENDED", superId, null));
    Map<String, Object> reactivated = service.update(superAdmin, opsId, null, null, "ACTIVE");
    assertThat(reactivated.get("status")).isEqualTo("ACTIVE");
    assertThat(revoke.revoked).doesNotContain(opsId);
  }

  @Test
  void demoteNonLastSuperAndNameNullEmailNull() {
    UUID second = Ids.newId();
    store.put(row(second, "S2", "s2@test.in", "admin_super", "ACTIVE", null, null));
    Map<String, Object> demoted =
        service.update(
            principal(second, AuthRole.ADMIN_SUPER), superId, null, "admin_finance", null);
    assertThat(demoted.get("role")).isEqualTo("admin_finance");

    assertThatThrownBy(() -> service.invite(superAdmin, null, "x@test.in", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", null, "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "  ", "x@test.in", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.list(opsAdmin, 1, 20, null, null, "   ").meta()).isNotNull();
  }

  @Test
  void allAdminRolesCanListAndRateLimitHits() {
    for (AuthRole role :
        List.of(AuthRole.ADMIN_FINANCE, AuthRole.ADMIN_SUPPORT, AuthRole.ADMIN_COMPLIANCE)) {
      assertThat(service.list(principal(Ids.newId(), role), 1, 5, null, null, null).data())
          .isNotNull();
    }

    AdminStaffService limited =
        new AdminStaffService(
            store,
            revoke,
            audit,
            email,
            new RateLimiter() {
              @Override
              public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
              }

              @Override
              public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
                return 9;
              }

              @Override
              public void putCooldown(String key, int ttlSeconds) {}

              @Override
              public int cooldownRemainingSeconds(String key) {
                return 0;
              }
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> limited.list(opsAdmin, 1, 20, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void lastSuperFalseWhenMultipleAndBlankToNull() {
    UUID second = Ids.newId();
    store.put(row(second, "S2", "s2@test.in", "admin_super", "ACTIVE", null, null));
    // suspending a non-last super is ok
    service.update(principal(second, AuthRole.ADMIN_SUPER), superId, null, null, "SUSPENDED");
    assertThat(revoke.revoked).contains(superId);
  }

  @Test
  void suspendedSuperNameChangeAndEmptyEmail() {
    UUID suspendedSuper = Ids.newId();
    store.put(row(suspendedSuper, "Sus", "sus@test.in", "admin_super", "SUSPENDED", null, null));
    Map<String, Object> renamed =
        service.update(superAdmin, suspendedSuper, "Sus Renamed", null, null);
    assertThat(renamed.get("name")).isEqualTo("Sus Renamed");

    assertThatThrownBy(() -> service.invite(superAdmin, "N", "", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(superAdmin, "N", "   ", "admin_support", true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void nameOnlyOnLastSuperDoesNotTripLastSuperGuard() {
    store.clear();
    store.put(row(superId, "Super", "super@test.in", "admin_super", "ACTIVE", null, null));
    Map<String, Object> renamed = service.update(superAdmin, superId, "Still Super", null, null);
    assertThat(renamed.get("name")).isEqualTo("Still Super");
    assertThat(renamed.get("role")).isEqualTo("admin_super");
  }

  @Test
  void listResultAndPageResultNullSafe() {
    assertThat(new ListResult(null, PaginationMeta.of(1, 20, 0)).data()).isEmpty();
    assertThat(new PageResult(null, 0).items()).isEmpty();
  }

  private static MedmatePrincipal principal(UUID id, AuthRole role) {
    return new MedmatePrincipal(id, role, null, TokenScope.FULL, "jti");
  }

  private static AdminStaffRow row(
      UUID id,
      String name,
      String email,
      String role,
      String status,
      UUID invitedBy,
      Instant inviteExpires) {
    return new AdminStaffRow(
        id, name, email, role, status, false, null, invitedBy, inviteExpires, NOW, NOW, null);
  }

  private static final class FakeStore implements AdminStaffStore {
    final Map<UUID, AdminStaffRow> byId = new HashMap<>();
    final Map<UUID, List<AuditTrailEntry>> trail = new HashMap<>();

    void put(AdminStaffRow row) {
      byId.put(row.id(), row);
    }

    void clear() {
      byId.clear();
    }

    @Override
    public Optional<AdminStaffRow> findById(UUID id) {
      return Optional.ofNullable(byId.get(id)).filter(r -> r.deletedAt() == null);
    }

    @Override
    public Optional<AdminStaffRow> findByEmail(String email) {
      return byId.values().stream()
          .filter(r -> r.deletedAt() == null && r.email().equalsIgnoreCase(email))
          .findFirst();
    }

    @Override
    public Optional<InviterRef> findInviter(UUID id) {
      AdminStaffRow row = byId.get(id);
      return row == null ? Optional.empty() : Optional.of(new InviterRef(row.id(), row.name()));
    }

    @Override
    public boolean emailExists(String email) {
      return byId.values().stream().anyMatch(r -> r.email().equalsIgnoreCase(email));
    }

    @Override
    public long countActiveSuperAdmins() {
      return byId.values().stream()
          .filter(
              r ->
                  r.deletedAt() == null
                      && "admin_super".equals(r.role())
                      && "ACTIVE".equals(r.status()))
          .count();
    }

    @Override
    public PageResult list(String role, String status, String search, int page, int limit) {
      List<AdminStaffRow> filtered =
          byId.values().stream()
              .filter(r -> r.deletedAt() == null)
              .filter(r -> role == null || role.equals(r.role()))
              .filter(r -> status == null || status.equals(r.status()))
              .filter(
                  r ->
                      search == null
                          || r.name().toLowerCase().contains(search.toLowerCase())
                          || r.email().toLowerCase().contains(search.toLowerCase()))
              .toList();
      int from = Math.max(0, (page - 1) * limit);
      int to = Math.min(filtered.size(), from + limit);
      List<AdminStaffRow> pageItems =
          from >= filtered.size() ? List.of() : filtered.subList(from, to);
      return new PageResult(pageItems, filtered.size());
    }

    @Override
    public void insertInvited(
        UUID id,
        String name,
        String email,
        String role,
        UUID invitedBy,
        String inviteTokenHash,
        Instant inviteExpiresAt,
        Instant now) {
      put(row(id, name, email, role, "INVITED", invitedBy, inviteExpiresAt));
    }

    @Override
    public void refreshInvite(
        UUID id,
        String name,
        String role,
        UUID invitedBy,
        String inviteTokenHash,
        Instant inviteExpiresAt,
        Instant updatedAt) {
      AdminStaffRow old = byId.get(id);
      put(
          new AdminStaffRow(
              id,
              name,
              old.email(),
              role,
              "INVITED",
              old.mfaEnabled(),
              old.lastActiveAt(),
              invitedBy,
              inviteExpiresAt,
              old.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public void update(UUID id, String name, String role, String status, Instant updatedAt) {
      AdminStaffRow old = byId.get(id);
      put(
          new AdminStaffRow(
              id,
              name,
              old.email(),
              role,
              status,
              old.mfaEnabled(),
              old.lastActiveAt(),
              old.invitedBy(),
              old.inviteExpiresAt(),
              old.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt) {
      AdminStaffRow old = byId.get(id);
      byId.put(
          id,
          new AdminStaffRow(
              id,
              old.name(),
              old.email(),
              old.role(),
              "SUSPENDED",
              old.mfaEnabled(),
              old.lastActiveAt(),
              old.invitedBy(),
              old.inviteExpiresAt(),
              old.createdAt(),
              deletedAt,
              deletedAt));
    }

    @Override
    public void setResetToken(
        UUID id, String resetTokenHash, Instant expiresAt, Instant updatedAt) {
      // no-op for unit test
    }

    @Override
    public List<AuditTrailEntry> listAuditTrail(UUID staffId) {
      return trail.getOrDefault(staffId, List.of());
    }
  }

  private static final class RecordingRevoke implements AdminSessionRevokePort {
    final Set<UUID> revoked = new HashSet<>();

    @Override
    public void revokeAllSessions(UUID staffId) {
      revoked.add(staffId);
    }
  }

  private static final class RecordingAudit implements AdminAuditAppendPort {
    final List<String> actions = new ArrayList<>();

    @Override
    public void append(
        String entityType,
        UUID actorId,
        String actorRole,
        UUID entityId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
      actions.add(action);
    }
  }

  private static final class RecordingEmail implements AdminStaffEmailPort {
    final List<String> invites = new ArrayList<>();
    final List<String> resets = new ArrayList<>();

    @Override
    public void sendInvite(
        UUID staffId, String email, String name, String plaintextToken, Instant expiresAt) {
      invites.add(email);
    }

    @Override
    public void sendPasswordReset(
        UUID staffId, String email, String name, String plaintextToken, Instant expiresAt) {
      resets.add(email);
    }
  }
}
