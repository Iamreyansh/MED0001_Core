package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.domain.BackupCodes;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminSetupMfaServiceTest {

  private static final Instant NOW = Instant.now();
  private static final byte[] AES_KEY = new byte[32];

  private final MutableClock clock = new MutableClock(NOW);
  private FakeStaffStore staffStore;
  private AesGcmCipher cipher;
  private InMemoryRateLimiter rateLimiter;
  private AdminSetupMfaService service;
  private SecureRandom fixedRandom;
  private final UUID adminId = Ids.newId();

  @BeforeEach
  void setUp() {
    staffStore = new FakeStaffStore();
    fixedRandom = new SecureRandom(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
    cipher = new AesGcmCipher(AES_KEY, fixedRandom);
    rateLimiter = new InMemoryRateLimiter(clock);
    service =
        new AdminSetupMfaService(
            staffStore,
            cipher,
            rateLimiter,
            clock,
            fixedRandom,
            () -> java.security.MessageDigest.getInstance("SHA-256"));

    staffStore.byId.put(
        adminId,
        new AdminStaffRecord(
            adminId,
            "Ops Admin",
            "ops@test.in",
            "hash",
            "admin_operations",
            "ACTIVE",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
  }

  @Test
  void autowiredConstructorCoversDefaults() {
    AdminSetupMfaService autowired =
        new AdminSetupMfaService(staffStore, cipher, rateLimiter, clock);
    AdminSetupMfaResult result = autowired.setup(adminId);
    assertThat(result.backupCodes()).hasSize(BackupCodes.COUNT);
  }

  @Test
  void setupWhenMfaNotEnabled() {
    AdminSetupMfaResult result = service.setup(adminId);

    assertThat(result.backupCodes()).hasSize(BackupCodes.COUNT);
    assertThat(result.totpUri()).contains("secret=");
    assertThat(result.totpSecret()).isNotBlank();

    AdminStaffRecord saved = staffStore.byId.get(adminId);
    assertThat(saved.mfaEnabled()).isFalse();
    assertThat(saved.encryptedTotpSecret()).isNotBlank();
    assertThat(cipher.decrypt(saved.encryptedTotpSecret())).isEqualTo(result.totpSecret());
    assertThat(saved.backupCodes()).hasSize(BackupCodes.COUNT);
  }

  @Test
  void secondSetupReplacesUnenrolledSecret() {
    AdminSetupMfaResult first = service.setup(adminId);
    String firstEncrypted = staffStore.byId.get(adminId).encryptedTotpSecret();

    fixedRandom = new SecureRandom(new byte[] {1, 0, 0, 1, 0, 0, 1, 0});
    service =
        new AdminSetupMfaService(
            staffStore,
            cipher,
            rateLimiter,
            clock,
            fixedRandom,
            () -> java.security.MessageDigest.getInstance("SHA-256"));
    AdminSetupMfaResult second = service.setup(adminId);

    AdminStaffRecord saved = staffStore.byId.get(adminId);
    assertThat(saved.mfaEnabled()).isFalse();
    assertThat(saved.encryptedTotpSecret()).isNotEqualTo(firstEncrypted);
    assertThat(second.totpSecret()).isNotEqualTo(first.totpSecret());
  }

  @Test
  void mfaAlreadyEnrolled() {
    staffStore.byId.put(
        adminId,
        new AdminStaffRecord(
            adminId,
            "Ops Admin",
            "ops@test.in",
            "hash",
            "admin_operations",
            "ACTIVE",
            true,
            "enc",
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));

    assertThatThrownBy(() -> service.setup(adminId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MFA_ALREADY_ENROLLED");
  }

  @Test
  void unauthorizedWhenAdminIdNull() {
    assertThatThrownBy(() -> service.setup(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void unauthorizedWhenAdminMissing() {
    assertThatThrownBy(() -> service.setup(Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void forbiddenForInvalidRole() {
    UUID badRoleId = Ids.newId();
    staffStore.byId.put(
        badRoleId,
        new AdminStaffRecord(
            badRoleId,
            "Bad",
            "bad@test.in",
            "hash",
            "not_a_role",
            "ACTIVE",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));

    assertThatThrownBy(() -> service.setup(badRoleId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void rateLimitFivePerHour() {
    for (int i = 0; i < 5; i++) {
      service.setup(adminId);
    }
    assertThatThrownBy(() -> service.setup(adminId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IP_RATE_LIMITED");
  }

  @Test
  void buildUriEncodesIssuerAndEmail() {
    String uri = AdminSetupMfaService.buildUri("ops@test.in", "JBSWY3DPEHPK3PXP");
    assertThat(uri).startsWith("otpauth://totp/");
    assertThat(uri).contains("secret=JBSWY3DPEHPK3PXP");
    assertThat(uri).contains("issuer=NammaMedMate");
    assertThat(uri).contains("digits=" + com.nammamedmate.auth.domain.Totp.DIGITS);
  }

  @Test
  void sha256HexThrowsOnDigestError() {
    AdminSetupMfaService badDigest =
        new AdminSetupMfaService(
            staffStore,
            cipher,
            rateLimiter,
            clock,
            new SecureRandom(),
            () -> {
              throw new java.security.NoSuchAlgorithmException("test");
            });
    assertThatThrownBy(() -> badDigest.sha256Hex("value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 not available");
  }

  private static final class FakeStaffStore implements AdminStaffStore {
    final Map<UUID, AdminStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<AdminStaffRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public Optional<AdminStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public AdminStaffRecord save(AdminStaffRecord staff) {
      byId.put(staff.id(), staff);
      return staff;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
