package com.nammamedmate.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.application.port.out.LoginAuditRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyAuthStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  @Test
  void pharmacyStaffStoreRoundTrip() {
    PharmacyStaffJpaRepository repo = mock(PharmacyStaffJpaRepository.class);
    JpaPharmacyStaffStore store = new JpaPharmacyStaffStore(repo);

    UUID id = Ids.newId();
    PharmacyStaffRecord record =
        new PharmacyStaffRecord(
            id,
            "Priya",
            "priya@test.in",
            null,
            "hash",
            "pinhash",
            "ACTIVE",
            0,
            null,
            null,
            NOW,
            null,
            NOW,
            NOW);

    store.save(record);
    verify(repo).save(any(PharmacyStaffEntity.class));

    PharmacyStaffEntity entity =
        new PharmacyStaffEntity(
            id,
            "Priya",
            "priya@test.in",
            null,
            "hash",
            "pinhash",
            "ACTIVE",
            (short) 0,
            null,
            null,
            NOW,
            null,
            NOW,
            NOW);
    when(repo.findByEmailAndDeletedAtIsNull("priya@test.in")).thenReturn(Optional.of(entity));

    Optional<PharmacyStaffRecord> found = store.findByEmail("priya@test.in");
    assertThat(found).isPresent();
    assertThat(found.get().name()).isEqualTo("Priya");
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getEmail()).isEqualTo("priya@test.in");
    assertThat(entity.getPosPinHash()).isEqualTo("pinhash");
    assertThat(entity.getStatus()).isEqualTo("ACTIVE");
    assertThat(entity.getLockedUntil()).isNull();
    assertThat(entity.getLastFailedAt()).isNull();
    assertThat(entity.getLastLoginAt()).isEqualTo(NOW);
    assertThat(entity.getInvitedBy()).isNull();
    assertThat(entity.getDeletedAt()).isNull();

    // phone lookup
    PharmacyStaffEntity phoneEntity =
        new PharmacyStaffEntity(
            id,
            "Kavya",
            null,
            "+919876543210",
            "hash",
            null,
            "ACTIVE",
            (short) 0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(repo.findByPhoneAndDeletedAtIsNull("+919876543210")).thenReturn(Optional.of(phoneEntity));
    Optional<PharmacyStaffRecord> byPhone = store.findByPhone("+919876543210");
    assertThat(byPhone).isPresent();
    assertThat(byPhone.get().phone()).isEqualTo("+919876543210");

    // id lookup
    when(repo.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));
    Optional<PharmacyStaffRecord> byId = store.findById(id);
    assertThat(byId).isPresent();

    // not found
    when(repo.findByEmailAndDeletedAtIsNull("x@y.com")).thenReturn(Optional.empty());
    assertThat(store.findByEmail("x@y.com")).isEmpty();

    // save with createdAt=null covers the ternary fallback: createdAt != null ? createdAt : now
    PharmacyStaffRecord noCreatedAt =
        new PharmacyStaffRecord(
            id,
            "Priya",
            "priya@test.in",
            null,
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            null,
            NOW);
    store.save(noCreatedAt);
    verify(repo, org.mockito.Mockito.times(2)).save(any(PharmacyStaffEntity.class));
  }

  @Test
  void pharmacyStoreRoundTrip() {
    PharmacyJpaRepository repo = mock(PharmacyJpaRepository.class);
    JpaPharmacyStore store = new JpaPharmacyStore(repo);

    UUID id = Ids.newId();
    PharmacyEntity entity =
        new PharmacyEntity(id, "Sri Rama Medicals", null, "Bengaluru", "GROWTH", NOW, NOW);
    when(repo.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    Optional<PharmacyRecord> found = store.findById(id);
    assertThat(found).isPresent();
    assertThat(found.get().name()).isEqualTo("Sri Rama Medicals");
    assertThat(found.get().subscriptionPlan()).isEqualTo("GROWTH");
    assertThat(entity.getLogoUrl()).isNull();
    assertThat(entity.getCity()).isEqualTo("Bengaluru");
    assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
    assertThat(entity.getDeletedAt()).isNull();

    when(repo.findByIdAndDeletedAtIsNull(Ids.newId())).thenReturn(Optional.empty());
    assertThat(store.findById(Ids.newId())).isEmpty();
  }

  @Test
  void loginAuditStoreSaves() {
    LoginAuditJpaRepository repo = mock(LoginAuditJpaRepository.class);
    JpaLoginAuditStore store = new JpaLoginAuditStore(repo);

    UUID id = Ids.newId();
    UUID staffId = Ids.newId();
    LoginAuditRecord record =
        new LoginAuditRecord(
            id,
            "pharmacy_staff",
            "priya@test.in",
            staffId,
            false,
            "INVALID_CREDENTIALS",
            "1.1.1.1",
            "ua",
            NOW);
    store.save(record);
    verify(repo).save(any(LoginAuditEntity.class));

    LoginAuditEntity entity =
        new LoginAuditEntity(
            id,
            "pharmacy_staff",
            "priya@test.in",
            staffId,
            false,
            "INVALID_CREDENTIALS",
            "1.1.1.1",
            "ua",
            NOW);
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getActorType()).isEqualTo("pharmacy_staff");
    assertThat(entity.getIdentifier()).isEqualTo("priya@test.in");
    assertThat(entity.getStaffId()).isEqualTo(staffId);
    assertThat(entity.isSuccess()).isFalse();
    assertThat(entity.getFailureReason()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(entity.getIpAddress()).isEqualTo("1.1.1.1");
    assertThat(entity.getUserAgent()).isEqualTo("ua");
    assertThat(entity.getCreatedAt()).isEqualTo(NOW);
  }

  @Test
  void pharmacyAssignmentStoreProjectionMapping() {
    PharmacyAssignmentJpaRepository repo = mock(PharmacyAssignmentJpaRepository.class);
    JpaPharmacyAssignmentStore store = new JpaPharmacyAssignmentStore(repo);

    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    UUID assignId = Ids.newId();

    var projection =
        mockProjection(assignId, staffId, pharmacyId, "owner", "Sri Rama Medicals", true);
    when(repo.findActiveByStaffIdOrderByJoinedAt(staffId)).thenReturn(List.of(projection));

    List<PharmacyAssignmentRecord> list = store.listActiveByStaffId(staffId);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).roleCode()).isEqualTo("owner");
    assertThat(list.get(0).pharmacyName()).isEqualTo("Sri Rama Medicals");
    assertThat(list.get(0).isActive()).isTrue();

    when(repo.findActiveByStaffIdAndPharmacyId(staffId, pharmacyId))
        .thenReturn(Optional.of(projection));
    Optional<PharmacyAssignmentRecord> found = store.findActive(staffId, pharmacyId);
    assertThat(found).isPresent();
    assertThat(found.get().pharmacyId()).isEqualTo(pharmacyId);
  }

  @Test
  void pharmacyAssignmentEntityGetters() {
    UUID id = Ids.newId();
    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    UUID roleId = Ids.newId();
    Instant joined = Instant.parse("2026-01-01T00:00:00Z");
    Instant removed = Instant.parse("2026-06-01T00:00:00Z");

    PharmacyAssignmentEntity e =
        new PharmacyAssignmentEntity(id, staffId, pharmacyId, roleId, true, joined, removed);

    assertThat(e.getId()).isEqualTo(id);
    assertThat(e.getStaffId()).isEqualTo(staffId);
    assertThat(e.getPharmacyId()).isEqualTo(pharmacyId);
    assertThat(e.getRoleId()).isEqualTo(roleId);
    assertThat(e.isActive()).isTrue();
    assertThat(e.getJoinedAt()).isEqualTo(joined);
    assertThat(e.getRemovedAt()).isEqualTo(removed);
  }

  @Test
  void protectedNoArgConstructorsForJpa() throws Exception {
    assertEntityHasNoArgCtor(PharmacyStaffEntity.class);
    assertEntityHasNoArgCtor(PharmacyAssignmentEntity.class);
    assertEntityHasNoArgCtor(PharmacyEntity.class);
    assertEntityHasNoArgCtor(LoginAuditEntity.class);
    assertEntityHasNoArgCtor(PharmacyRoleEntity.class);
    assertEntityHasNoArgCtor(PermissionEntity.class);
  }

  @Test
  void pharmacyRoleAndPermissionStores() {
    PharmacyRoleJpaRepository roleRepo = mock(PharmacyRoleJpaRepository.class);
    JpaPharmacyRoleStore roleStore = new JpaPharmacyRoleStore(roleRepo);
    UUID roleId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    PharmacyRoleEntity entity =
        new PharmacyRoleEntity(
            roleId,
            pharmacyId,
            "senior",
            "senior",
            "Senior",
            false,
            new String[] {"orders:read"},
            null,
            NOW,
            NOW,
            null);
    when(roleRepo.findBySystemTrueAndDeletedAtIsNullOrderByCodeAsc()).thenReturn(List.of(entity));
    when(roleRepo.findByPharmacyIdAndDeletedAtIsNullOrderByCodeAsc(pharmacyId))
        .thenReturn(List.of(entity));
    when(roleRepo.findByIdAndDeletedAtIsNull(roleId)).thenReturn(Optional.of(entity));
    when(roleRepo.findBySystemTrueAndCodeAndDeletedAtIsNull("owner"))
        .thenReturn(Optional.of(entity));
    when(roleRepo.findByPharmacyIdAndCodeAndDeletedAtIsNull(pharmacyId, "senior"))
        .thenReturn(Optional.of(entity));
    when(roleRepo.countActiveStaff(roleId, pharmacyId)).thenReturn(2);
    when(roleRepo.countActiveStaffGlobal(roleId)).thenReturn(3);
    when(roleRepo.save(any(PharmacyRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThat(roleStore.listSystemRoles()).hasSize(1);
    assertThat(roleStore.listCustomByPharmacy(pharmacyId)).hasSize(1);
    assertThat(roleStore.findById(roleId)).isPresent();
    assertThat(roleStore.findSystemByCode("owner")).isPresent();
    assertThat(roleStore.findActiveByPharmacyAndCode(pharmacyId, "senior")).isPresent();
    assertThat(roleStore.countActiveStaff(roleId, pharmacyId)).isEqualTo(2);
    assertThat(roleStore.countActiveStaff(roleId, null)).isEqualTo(3);
    assertThat(
            roleStore
                .save(
                    new com.nammamedmate.auth.application.port.out.PharmacyRoleRecord(
                        roleId,
                        pharmacyId,
                        "senior",
                        "Senior",
                        false,
                        List.of("orders:read"),
                        null,
                        NOW,
                        NOW,
                        null))
                .code())
        .isEqualTo("senior");

    PharmacyRoleEntity emptyPerms =
        new PharmacyRoleEntity(
            roleId, null, "owner", "owner", "Owner", true, null, null, NOW, NOW, null);
    when(roleRepo.findByIdAndDeletedAtIsNull(roleId)).thenReturn(Optional.of(emptyPerms));
    assertThat(roleStore.findById(roleId).orElseThrow().permissions()).isEmpty();
    emptyPerms.setPermissions(null);
    assertThat(emptyPerms.getPermissions()).isNull();
    emptyPerms.setPermissions(new String[] {"orders:*"});
    emptyPerms.setUpdatedAt(NOW);
    emptyPerms.setDeletedAt(NOW);
    assertThat(emptyPerms.getPermissions()).containsExactly("orders:*");
    // clone defensive copy
    String[] exposed = emptyPerms.getPermissions();
    exposed[0] = "tampered";
    assertThat(emptyPerms.getPermissions()).containsExactly("orders:*");
    assertThat(emptyPerms.getUpdatedAt()).isEqualTo(NOW);
    assertThat(emptyPerms.getDeletedAt()).isEqualTo(NOW);
    assertThat(emptyPerms.getName()).isEqualTo("owner");
    assertThat(emptyPerms.getCreatedBy()).isNull();
    assertThat(emptyPerms.getCreatedAt()).isEqualTo(NOW);
    assertThat(emptyPerms.getPharmacyId()).isNull();
    assertThat(emptyPerms.isSystem()).isTrue();
    assertThat(emptyPerms.getDisplayName()).isEqualTo("Owner");
    assertThat(emptyPerms.getCode()).isEqualTo("owner");
    assertThat(emptyPerms.getId()).isEqualTo(roleId);

    PermissionJpaRepository permRepo = mock(PermissionJpaRepository.class);
    JpaPermissionCatalogStore permStore = new JpaPermissionCatalogStore(permRepo);
    PermissionEntity pe = new PermissionEntity("orders", "read", "admin", "d");
    when(permRepo.findByDomainOrderByResourceAscActionAsc("admin")).thenReturn(List.of(pe));
    when(permRepo.findByDomainAndResourceOrderByActionAsc("admin", "orders"))
        .thenReturn(List.of(pe));
    when(permRepo.findByDomainAndResourceAndAction("admin", "orders", "read"))
        .thenReturn(Optional.of(pe));
    assertThat(permStore.listByDomain("admin")).hasSize(1);
    assertThat(permStore.listByDomainAndResource("admin", "orders")).hasSize(1);
    assertThat(permStore.find("admin", "orders", "read")).isPresent();
    assertThat(pe.getResource()).isEqualTo("orders");
    assertThat(pe.getAction()).isEqualTo("read");
    assertThat(pe.getDomain()).isEqualTo("admin");
    assertThat(pe.getDescription()).isEqualTo("d");

    PermissionEntity.Pk pk = new PermissionEntity.Pk("a", "b", "admin");
    assertThat(pk).isEqualTo(new PermissionEntity.Pk("a", "b", "admin"));
    assertThat(pk.hashCode()).isEqualTo(new PermissionEntity.Pk("a", "b", "admin").hashCode());
    assertThat(pk).isNotEqualTo(new PermissionEntity.Pk("x", "b", "admin"));
    assertThat(pk).isNotEqualTo(new PermissionEntity.Pk("a", "x", "admin"));
    assertThat(pk).isNotEqualTo(new PermissionEntity.Pk("a", "b", "pharmacy"));
    assertThat(pk).isNotEqualTo("nope");
    assertThat(pk.equals(pk)).isTrue();
    assertThat(new PermissionEntity.Pk()).isNotNull();
  }

  private static PharmacyAssignmentJpaRepository.AssignmentProjection mockProjection(
      UUID id,
      UUID staffId,
      UUID pharmacyId,
      String roleCode,
      String pharmacyName,
      boolean active) {
    return new PharmacyAssignmentJpaRepository.AssignmentProjection() {
      @Override
      public UUID getId() {
        return id;
      }

      @Override
      public UUID getStaff_id() {
        return staffId;
      }

      @Override
      public UUID getPharmacy_id() {
        return pharmacyId;
      }

      @Override
      public String getRole_code() {
        return roleCode;
      }

      @Override
      public boolean getIs_active() {
        return active;
      }

      @Override
      public Instant getJoined_at() {
        return NOW;
      }

      @Override
      public Instant getRemoved_at() {
        return null;
      }

      @Override
      public String getPharmacy_name() {
        return pharmacyName;
      }
    };
  }

  private static void assertEntityHasNoArgCtor(Class<?> type) throws Exception {
    java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}
