package com.nammamedmate.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAuthStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

  @Test
  void adminStaffStoreRoundTrip() {
    AdminStaffJpaRepository repo = mock(AdminStaffJpaRepository.class);
    JpaAdminStaffStore store = new JpaAdminStaffStore(repo);

    UUID id = Ids.newId();
    Map<String, Object> code = new HashMap<>();
    code.put("hash", "abc");
    code.put("used_at", null);
    AdminStaffRecord record =
        new AdminStaffRecord(
            id,
            "Ayesha",
            "ayesha@test.in",
            "hash",
            "admin_super",
            "ACTIVE",
            true,
            "enc-secret",
            List.of(code),
            0,
            null,
            null,
            NOW,
            NOW,
            null,
            NOW,
            NOW);

    store.save(record);
    verify(repo).save(any(AdminStaffEntity.class));

    AdminStaffEntity entity =
        new AdminStaffEntity(
            id,
            "Ayesha",
            "ayesha@test.in",
            "hash",
            "admin_super",
            "ACTIVE",
            true,
            "enc-secret",
            List.of(code),
            (short) 0,
            null,
            null,
            NOW,
            NOW,
            null,
            NOW,
            NOW);
    when(repo.findByEmailAndDeletedAtIsNull("ayesha@test.in")).thenReturn(Optional.of(entity));
    when(repo.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    Optional<AdminStaffRecord> byEmail = store.findByEmail("ayesha@test.in");
    assertThat(byEmail).isPresent();
    assertThat(byEmail.get().name()).isEqualTo("Ayesha");
    assertThat(byEmail.get().mfaEnabled()).isTrue();
    assertThat(byEmail.get().encryptedTotpSecret()).isEqualTo("enc-secret");
    assertThat(byEmail.get().backupCodes()).hasSize(1);

    Optional<AdminStaffRecord> byId = store.findById(id);
    assertThat(byId).isPresent();

    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getEmail()).isEqualTo("ayesha@test.in");
    assertThat(entity.getRole()).isEqualTo("admin_super");
    assertThat(entity.getStatus()).isEqualTo("ACTIVE");
    assertThat(entity.isMfaEnabled()).isTrue();
    assertThat(entity.getTotpSecret()).isEqualTo("enc-secret");
    assertThat(entity.getBackupCodes()).hasSize(1);
    assertThat(entity.getFailedLoginAttempts()).isZero();
    assertThat(entity.getLockedUntil()).isNull();
    assertThat(entity.getLastFailedAt()).isNull();
    assertThat(entity.getLastLoginAt()).isEqualTo(NOW);
    assertThat(entity.getLastActiveAt()).isEqualTo(NOW);
    assertThat(entity.getInvitedBy()).isNull();
    assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
    assertThat(entity.getDeletedAt()).isNull();
    assertThat(entity.getPasswordHash()).isEqualTo("hash");
    assertThat(entity.getName()).isEqualTo("Ayesha");

    when(repo.findByEmailAndDeletedAtIsNull("missing@test.in")).thenReturn(Optional.empty());
    assertThat(store.findByEmail("missing@test.in")).isEmpty();

    AdminStaffRecord emptyBackup =
        new AdminStaffRecord(
            id,
            "Ops",
            "ops@test.in",
            "hash",
            "admin_operations",
            "ACTIVE",
            false,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    assertThat(emptyBackup.backupCodes()).isEmpty();
    store.save(emptyBackup);
    verify(repo, times(2)).save(any(AdminStaffEntity.class));

    AdminStaffEntity nullBackupEntity =
        new AdminStaffEntity(
            id,
            "Ops",
            "ops@test.in",
            "hash",
            "admin_operations",
            "ACTIVE",
            false,
            null,
            null,
            (short) 0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    assertThat(JpaAdminStaffStore.toRecord(nullBackupEntity).backupCodes()).isEmpty();
  }

  @Test
  void adminAuthEventStoreSaves() {
    AdminAuthEventJpaRepository repo = mock(AdminAuthEventJpaRepository.class);
    JpaAdminAuthEventStore store = new JpaAdminAuthEventStore(repo);

    UUID id = Ids.newId();
    UUID adminId = Ids.newId();
    AdminAuthEventRecord record =
        new AdminAuthEventRecord(
            id, adminId, "LOGIN_FAILED", "1.2.3.4", "ua", Map.of("email", "a@b.com"), NOW);
    store.save(record);
    verify(repo).save(any(AdminAuthEventEntity.class));

    store.save(new AdminAuthEventRecord(id, adminId, "LOGIN_SUCCESS", null, null, null, NOW));
    store.save(new AdminAuthEventRecord(id, adminId, "LOGIN_SUCCESS", "  ", null, null, NOW));

    AdminAuthEventEntity entity =
        new AdminAuthEventEntity(
            id, adminId, "LOGIN_FAILED", "1.2.3.4", "ua", Map.of("email", "a@b.com"), NOW);
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getAdminId()).isEqualTo(adminId);
    assertThat(entity.getEventType()).isEqualTo("LOGIN_FAILED");
    assertThat(entity.getIpAddress()).isEqualTo("1.2.3.4");
    assertThat(entity.getUserAgent()).isEqualTo("ua");
    assertThat(entity.getMetadata()).containsEntry("email", "a@b.com");
    assertThat(entity.getCreatedAt()).isEqualTo(NOW);

    AdminAuthEventEntity noMeta =
        new AdminAuthEventEntity(id, adminId, "LOGOUT", "0.0.0.0", null, null, NOW);
    assertThat(noMeta.getMetadata()).isNull();
    assertThat(noMeta.getUserAgent()).isNull();
  }

  @Test
  void protectedNoArgConstructorsForJpa() throws Exception {
    assertEntityHasNoArgCtor(AdminStaffEntity.class);
    assertEntityHasNoArgCtor(AdminAuthEventEntity.class);
  }

  private static void assertEntityHasNoArgCtor(Class<?> type) throws Exception {
    java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}
