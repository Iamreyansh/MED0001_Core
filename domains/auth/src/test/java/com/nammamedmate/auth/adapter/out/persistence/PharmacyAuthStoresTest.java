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
        mockProjection(assignId, staffId, pharmacyId, "pharmacy_owner", "Sri Rama Medicals", true);
    when(repo.findActiveByStaffIdOrderByJoinedAt(staffId)).thenReturn(List.of(projection));

    List<PharmacyAssignmentRecord> list = store.listActiveByStaffId(staffId);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).roleCode()).isEqualTo("pharmacy_owner");
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
