package com.nammamedmate.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.adapter.out.persistence.PharmacyAssignmentJpaRepository.AssignmentProjection;
import com.nammamedmate.auth.adapter.out.persistence.PharmacyAssignmentJpaRepository.StaffDirectoryProjection;
import com.nammamedmate.auth.application.port.out.PharmacyStaffInviteRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffPasswordResetRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaPharmacyStaffDirectoryAndInviteStoreTest {

  @Test
  void directoryDelegates() {
    PharmacyAssignmentJpaRepository repo = mock(PharmacyAssignmentJpaRepository.class);
    JpaPharmacyStaffDirectoryStore store = new JpaPharmacyStaffDirectoryStore(repo);
    UUID staff = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-31T06:00:00Z");
    AssignmentProjection assignment = mock(AssignmentProjection.class);
    when(assignment.getId()).thenReturn(UUID.randomUUID());
    when(assignment.getStaff_id()).thenReturn(staff);
    when(assignment.getPharmacy_id()).thenReturn(pharmacy);
    when(assignment.getRole_code()).thenReturn("cashier");
    when(assignment.getIs_active()).thenReturn(true);
    when(assignment.getJoined_at()).thenReturn(now);
    when(assignment.getRemoved_at()).thenReturn(null);
    when(assignment.getPharmacy_name()).thenReturn("Shop");
    when(repo.findByStaffIdAndPharmacyId(staff, pharmacy)).thenReturn(Optional.of(assignment));
    StaffDirectoryProjection row = mock(StaffDirectoryProjection.class);
    when(row.getStaff_id()).thenReturn(staff);
    when(row.getName()).thenReturn("Ada");
    when(row.getEmail()).thenReturn("a@x.com");
    when(row.getPhone()).thenReturn("1");
    when(row.getStatus()).thenReturn("ACTIVE");
    when(row.getRole_code()).thenReturn("cashier");
    when(row.getIs_active()).thenReturn(true);
    when(row.getJoined_at()).thenReturn(now);
    when(row.getPos_pin_set()).thenReturn(true);
    when(repo.listDirectory(pharmacy)).thenReturn(List.of(row));

    store.insertAssignment(UUID.randomUUID(), staff, pharmacy, UUID.randomUUID(), now);
    verify(repo).save(any(PharmacyAssignmentEntity.class));
    store.reactivateAssignment(staff, pharmacy, UUID.randomUUID());
    store.deactivateAssignment(staff, pharmacy, now);
    assertThat(store.findAssignment(staff, pharmacy)).isPresent();
    assertThat(store.listDirectory(pharmacy)).hasSize(1);
  }

  @Test
  void inviteStoreRoundTrip() {
    PharmacyStaffInviteJpaRepository repo = mock(PharmacyStaffInviteJpaRepository.class);
    JpaPharmacyStaffInviteStore store = new JpaPharmacyStaffInviteStore(repo);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-31T06:00:00Z");
    PharmacyStaffInviteRecord record =
        new PharmacyStaffInviteRecord(
            id, UUID.randomUUID(), UUID.randomUUID(), "hash", now, null, now);
    store.insert(record);
    verify(repo).save(any(PharmacyStaffInviteEntity.class));
    PharmacyStaffInviteEntity entity =
        new PharmacyStaffInviteEntity(
            id, record.staffId(), record.pharmacyId(), "hash", now, null, now);
    when(repo.findActiveByTokenHash("hash")).thenReturn(Optional.of(entity));
    assertThat(store.findActiveByTokenHash("hash")).isPresent();
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getStaffId()).isEqualTo(record.staffId());
    assertThat(entity.getPharmacyId()).isEqualTo(record.pharmacyId());
    assertThat(entity.getTokenHash()).isEqualTo("hash");
    assertThat(entity.getExpiresAt()).isEqualTo(now);
    assertThat(entity.getUsedAt()).isNull();
    assertThat(entity.getCreatedAt()).isEqualTo(now);
    store.markUsed(id, now);
    verify(repo).markUsed(id, now);
  }

  @Test
  void passwordResetStoreRoundTrip() {
    PharmacyStaffPasswordResetJpaRepository repo =
        mock(PharmacyStaffPasswordResetJpaRepository.class);
    JpaPharmacyStaffPasswordResetStore store = new JpaPharmacyStaffPasswordResetStore(repo);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-31T06:00:00Z");
    PharmacyStaffPasswordResetRecord record =
        new PharmacyStaffPasswordResetRecord(id, UUID.randomUUID(), "hash", now, null, now);
    store.insert(record);
    verify(repo).save(any(PharmacyStaffPasswordResetEntity.class));
    PharmacyStaffPasswordResetEntity entity =
        new PharmacyStaffPasswordResetEntity(id, record.staffId(), "hash", now, null, now);
    when(repo.findActiveByTokenHash("hash")).thenReturn(Optional.of(entity));
    assertThat(store.findActiveByTokenHash("hash")).isPresent();
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getStaffId()).isEqualTo(record.staffId());
    assertThat(entity.getTokenHash()).isEqualTo("hash");
    assertThat(entity.getExpiresAt()).isEqualTo(now);
    assertThat(entity.getUsedAt()).isNull();
    assertThat(entity.getCreatedAt()).isEqualTo(now);
    store.markUsed(id, now);
    verify(repo).markUsed(id, now);
  }
}
