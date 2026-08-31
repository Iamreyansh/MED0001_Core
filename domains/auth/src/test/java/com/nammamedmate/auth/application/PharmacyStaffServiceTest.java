package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.nammamedmate.auth.domain.PharmacyStaffTokens;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PharmacyStaffServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-31T06:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID ROLE_ID = UUID.randomUUID();

  private final PharmacyStaffStore staffStore = mock(PharmacyStaffStore.class);
  private final PharmacyStaffDirectoryStore directory = mock(PharmacyStaffDirectoryStore.class);
  private final PharmacyStaffInviteStore invites = mock(PharmacyStaffInviteStore.class);
  private final PharmacyStaffPasswordResetStore resets =
      mock(PharmacyStaffPasswordResetStore.class);
  private final PharmacyRoleStore roles = mock(PharmacyRoleStore.class);
  private final RbacPermissionService rbac = mock(RbacPermissionService.class);
  private final AuthSessionStore sessions = mock(AuthSessionStore.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final com.nammamedmate.kernel.ratelimit.RateLimiter rateLimiter =
      mock(com.nammamedmate.kernel.ratelimit.RateLimiter.class);
  private PharmacyStaffService service;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(OWNER_ID, AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  private final MedmatePrincipal manager =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(encoder.encode(anyString())).thenReturn("hashed");
    when(rbac.requirePharmacyAssignment(any()))
        .thenReturn(
            new PharmacyAssignmentRecord(
                UUID.randomUUID(), OWNER_ID, PHARMACY, "owner", true, NOW, null, "Shop"));
    when(roles.findSystemByCode("pharmacist"))
        .thenReturn(
            Optional.of(
                new PharmacyRoleRecord(
                    ROLE_ID,
                    null,
                    "pharmacist",
                    "Pharmacist",
                    true,
                    List.of(),
                    null,
                    NOW,
                    NOW,
                    null)));
    service =
        new PharmacyStaffService(
            staffStore,
            directory,
            invites,
            resets,
            roles,
            rbac,
            sessions,
            encoder,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listFiltersAndPaginates() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(directory.listDirectory(PHARMACY))
        .thenReturn(
            List.of(
                new PharmacyStaffDirectoryRow(
                    a, "Ada", "ada@x.com", "1", "ACTIVE", "pharmacist", true, NOW, true),
                new PharmacyStaffDirectoryRow(
                    b, "Bob", "bob@x.com", "2", "INVITED", "cashier", true, NOW, false),
                new PharmacyStaffDirectoryRow(
                    null, null, null, null, "ACTIVE", "delivery", true, null, false)));
    var all = service.list(owner, "ALL", "ada", 1, 20);
    assertThat(all.data()).hasSize(1);
    assertThat(all.data().getFirst()).containsEntry("pos_pin_set", true);
    var invited = service.list(owner, "invited", null, 1, 1);
    assertThat(invited.meta().total()).isEqualTo(1);
    var byEmail = service.list(owner, null, "bob@x.com", 1, 20);
    assertThat(byEmail.data()).hasSize(1);
    var byPhone = service.list(owner, " ", "2", 1, 20);
    assertThat(byPhone.data()).hasSize(1);
    var unfiltered = service.list(owner, null, null, 1, 20);
    assertThat(unfiltered.data()).hasSize(3);
    assertThat(unfiltered.data().get(2))
        .containsEntry("staff_id", null)
        .containsEntry("joined_at", null);
  }

  @Test
  void inviteCreatesNewStaffAndReactivatesExisting() {
    when(staffStore.findByEmail("new@x.com")).thenReturn(Optional.empty());
    Map<String, Object> created = service.invite(owner, "New", "New@x.com", "  ", "pharmacist");
    assertThat(created).containsKeys("invite_token", "staff_id");
    verify(directory).insertAssignment(any(), any(), eq(PHARMACY), eq(ROLE_ID), eq(NOW));
    verify(invites).insert(any());

    UUID existingId = UUID.randomUUID();
    when(staffStore.findByEmail("old@x.com"))
        .thenReturn(Optional.of(staff(existingId, "Old", "INVITED")));
    when(directory.findAssignment(existingId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), existingId, PHARMACY, "cashier", false, NOW, NOW, "Shop")));
    service.invite(owner, "Old", "old@x.com", "99", "pharmacist");
    verify(directory).reactivateAssignment(existingId, PHARMACY, ROLE_ID);

    UUID other = UUID.randomUUID();
    when(staffStore.findByEmail("other@x.com"))
        .thenReturn(Optional.of(staff(other, "O", "ACTIVE")));
    when(directory.findAssignment(other, PHARMACY)).thenReturn(Optional.empty());
    service.invite(owner, "O", "other@x.com", null, "pharmacist");
    verify(directory).insertAssignment(any(), eq(other), eq(PHARMACY), eq(ROLE_ID), eq(NOW));
  }

  @Test
  void inviteRejectsOwnerRoleDuplicateAndValidation() {
    assertThatThrownBy(() -> service.invite(owner, "A", "a@x.com", null, "owner"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(roles.findSystemByCode("ghost")).thenReturn(Optional.empty());
    when(roles.findActiveByPharmacyAndCode(PHARMACY, "ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.invite(owner, "A", "a@x.com", null, "ghost"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
    assertThatThrownBy(() -> service.invite(owner, " ", "a@x.com", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, null, "a@x.com", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A", "not-email", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A", null, null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A", "  ", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A".repeat(101), "a@x.com", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A", "a@x.com", null, "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(owner, "A", "a@x.com", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID existingId = UUID.randomUUID();
    when(staffStore.findByEmail("dup@x.com"))
        .thenReturn(Optional.of(staff(existingId, "D", "ACTIVE")));
    when(directory.findAssignment(existingId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), existingId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    assertThatThrownBy(() -> service.invite(owner, "D", "dup@x.com", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_ALREADY_ASSIGNED");
  }

  @Test
  void completeInviteActivatesAndRejectsBadTokens() {
    UUID staffId = UUID.randomUUID();
    UUID inviteId = UUID.randomUUID();
    String token = PharmacyStaffTokens.generate();
    when(invites.findActiveByTokenHash(PharmacyStaffTokens.sha256Hex(token)))
        .thenReturn(
            Optional.of(
                new PharmacyStaffInviteRecord(
                    inviteId, staffId, PHARMACY, "h", NOW.plusSeconds(60), null, NOW)));
    when(staffStore.findById(staffId)).thenReturn(Optional.of(staff(staffId, "A", "INVITED")));
    assertThat(service.completeInvite(token, "Passw0rd!")).containsEntry("status", "ACTIVE");
    verify(invites).markUsed(inviteId, NOW);

    assertThatThrownBy(() -> service.completeInvite(" ", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVITE_INVALID");
    assertThatThrownBy(() -> service.completeInvite(null, "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVITE_INVALID");
    assertThatThrownBy(() -> service.completeInvite("tok", "weak"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    assertThatThrownBy(() -> service.completeInvite("tok", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    assertThatThrownBy(() -> service.completeInvite("tok", "password1!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    assertThatThrownBy(() -> service.completeInvite("tok", "Password!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    assertThatThrownBy(() -> service.completeInvite("tok", "Password1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    when(invites.findActiveByTokenHash(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.completeInvite("missing", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVITE_INVALID");
    when(invites.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffInviteRecord(
                    inviteId, staffId, PHARMACY, "h", NOW.minusSeconds(1), null, NOW)));
    assertThatThrownBy(() -> service.completeInvite("expired", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVITE_EXPIRED");
    when(invites.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffInviteRecord(inviteId, staffId, PHARMACY, "h", null, null, NOW)));
    when(staffStore.findById(staffId)).thenReturn(Optional.of(staff(staffId, "A", "INVITED")));
    assertThat(service.completeInvite("no-expiry", "Passw0rd!")).containsEntry("status", "ACTIVE");
    when(invites.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffInviteRecord(
                    inviteId, staffId, PHARMACY, "h", NOW.plusSeconds(10), null, NOW)));
    when(staffStore.findById(staffId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.completeInvite("gone", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVITE_INVALID");
  }

  @Test
  void deactivateAndPosPinGuardLastOwnerAndSelf() {
    UUID staffId = UUID.randomUUID();
    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    when(staffStore.findById(staffId)).thenReturn(Optional.of(staff(staffId, "C", "ACTIVE")));
    assertThat(service.deactivate(owner, staffId)).containsEntry("status", "SUSPENDED");
    verify(sessions).revokeAllForUser(staffId, NOW);

    assertThatThrownBy(() -> service.deactivate(owner, OWNER_ID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNOT_DEACTIVATE_SELF");
    assertThatThrownBy(() -> service.deactivate(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(directory.findAssignment(staffId, PHARMACY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deactivate(owner, staffId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "owner", true, NOW, null, "Shop")));
    when(directory.listDirectory(PHARMACY))
        .thenReturn(
            List.of(
                new PharmacyStaffDirectoryRow(
                    staffId, "O", "o@x.com", null, "ACTIVE", "owner", true, NOW, false)));
    assertThatThrownBy(() -> service.deactivate(owner, staffId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("LAST_OWNER");
    UUID otherOwner = UUID.randomUUID();
    when(directory.listDirectory(PHARMACY))
        .thenReturn(
            List.of(
                new PharmacyStaffDirectoryRow(
                    staffId, "O", "o@x.com", null, "ACTIVE", "owner", true, NOW, false),
                new PharmacyStaffDirectoryRow(
                    otherOwner, "O2", "o2@x.com", null, "ACTIVE", "owner", true, NOW, false),
                new PharmacyStaffDirectoryRow(
                    UUID.randomUUID(),
                    "Old",
                    "old@x.com",
                    null,
                    "ACTIVE",
                    "owner",
                    false,
                    NOW,
                    false),
                new PharmacyStaffDirectoryRow(
                    UUID.randomUUID(),
                    "C",
                    "c@x.com",
                    null,
                    "ACTIVE",
                    "cashier",
                    true,
                    NOW,
                    false)));
    assertThat(service.deactivate(owner, staffId)).containsEntry("status", "SUSPENDED");

    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    assertThat(service.setPosPin(owner, staffId, "1234")).containsEntry("pos_pin_set", true);
    assertThatThrownBy(() -> service.setPosPin(owner, null, "1234"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setPosPin(owner, staffId, "12"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setPosPin(owner, staffId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(directory.findAssignment(staffId, PHARMACY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setPosPin(owner, staffId, "1234"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    when(staffStore.findById(staffId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setPosPin(owner, staffId, "1234"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void managerNeedsPermissionAndRateLimit() {
    doThrow(new AppException("INSUFFICIENT_PERMISSIONS", "no", 403))
        .when(rbac)
        .requirePermission(manager, "staff:manage");
    assertThatThrownBy(() -> service.invite(manager, "A", "a@x.com", null, "pharmacist"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_PERMISSIONS");
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(9);
    assertThatThrownBy(() -> service.list(owner, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void customRoleAndStaffManageSucceeds() {
    when(roles.findSystemByCode("floor")).thenReturn(Optional.empty());
    when(roles.findActiveByPharmacyAndCode(PHARMACY, "floor"))
        .thenReturn(
            Optional.of(
                new PharmacyRoleRecord(
                    ROLE_ID, PHARMACY, "floor", "Floor", false, List.of(), null, NOW, NOW, null)));
    when(staffStore.findByEmail("c@x.com")).thenReturn(Optional.empty());
    assertThat(service.invite(manager, "C", "c@x.com", "1", "floor"))
        .containsEntry("role", "floor");
  }

  @Test
  void requestPasswordResetIsGenericAndIssuesForActiveStaff() {
    assertThat(service.requestPasswordReset(null)).containsEntry("requested", true);
    assertThat(service.requestPasswordReset("not-an-id")).containsEntry("requested", true);
    when(staffStore.findByEmail("ada@x.com")).thenReturn(Optional.empty());
    assertThat(service.requestPasswordReset("ada@x.com")).containsEntry("requested", true);
    UUID staffId = UUID.randomUUID();
    when(staffStore.findByEmail("ada@x.com"))
        .thenReturn(Optional.of(staff(staffId, "Ada", "INVITED")));
    assertThat(service.requestPasswordReset("ada@x.com")).containsEntry("requested", true);
    when(staffStore.findByEmail("ada@x.com"))
        .thenReturn(Optional.of(staff(staffId, "Ada", "ACTIVE")));
    assertThat(service.requestPasswordReset("Ada@X.com")).containsEntry("requested", true);
    verify(resets).insert(any(PharmacyStaffPasswordResetRecord.class));
    when(staffStore.findByPhone("+919876543210"))
        .thenReturn(Optional.of(staff(staffId, "Ada", "ACTIVE")));
    assertThat(service.requestPasswordReset("+919876543210")).containsEntry("requested", true);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.requestPasswordReset("ada@x.com"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void completeAndIssuePasswordReset() {
    UUID staffId = UUID.randomUUID();
    UUID resetId = UUID.randomUUID();
    assertThatThrownBy(() -> service.completePasswordReset(" ", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");
    assertThatThrownBy(() -> service.completePasswordReset(null, "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");
    assertThatThrownBy(() -> service.completePasswordReset("tok", "weak"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("WEAK_PASSWORD");
    when(resets.findActiveByTokenHash(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.completePasswordReset("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");
    when(resets.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffPasswordResetRecord(
                    resetId, staffId, "h", NOW.minusSeconds(1), null, NOW)));
    assertThatThrownBy(() -> service.completePasswordReset("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_EXPIRED");
    when(resets.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffPasswordResetRecord(
                    resetId, staffId, "h", NOW.plusSeconds(60), null, NOW)));
    when(staffStore.findById(staffId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.completePasswordReset("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");
    when(staffStore.findById(staffId)).thenReturn(Optional.of(staff(staffId, "Ada", "ACTIVE")));
    when(resets.findActiveByTokenHash(anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyStaffPasswordResetRecord(resetId, staffId, "h", null, null, NOW)));
    assertThat(service.completePasswordReset("tok", "Passw0rd!")).containsEntry("status", "ACTIVE");
    verify(sessions).revokeAllForUser(staffId, NOW);

    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    assertThat(service.issuePasswordReset(owner, staffId)).containsKey("reset_token");
    assertThatThrownBy(() -> service.issuePasswordReset(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(directory.findAssignment(staffId, PHARMACY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.issuePasswordReset(owner, staffId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
    when(directory.findAssignment(staffId, PHARMACY))
        .thenReturn(
            Optional.of(
                new PharmacyAssignmentRecord(
                    UUID.randomUUID(), staffId, PHARMACY, "cashier", true, NOW, null, "Shop")));
    when(staffStore.findById(staffId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.issuePasswordReset(owner, staffId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_FOUND");
    when(staffStore.findById(staffId)).thenReturn(Optional.of(staff(staffId, "Ada", "INVITED")));
    assertThatThrownBy(() -> service.issuePasswordReset(owner, staffId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_NOT_ACTIVE");
  }

  private static PharmacyStaffRecord staff(UUID id, String name, String status) {
    return new PharmacyStaffRecord(
        id,
        name,
        name.toLowerCase() + "@x.com",
        "1",
        "hash",
        null,
        status,
        0,
        null,
        null,
        null,
        OWNER_ID,
        NOW,
        NOW);
  }
}
