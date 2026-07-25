package com.nammamedmate.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TotpBase32BackupCodesTest {

  private static final byte[] SECRET = "hello-totp-secret!!".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  @Test
  void totpGenerateAndVerifyCurrentWindow() {
    String code = Totp.generate(SECRET, NOW);
    assertThat(code).matches("\\d{6}");
    assertThat(Totp.verify(SECRET, code, NOW)).isTrue();
  }

  @Test
  void totpVerifyPreviousWindow() {
    Instant nextWindow = NOW.plusSeconds(Totp.PERIOD_SECONDS);
    String previousCode = Totp.generate(SECRET, NOW);
    assertThat(Totp.verify(SECRET, previousCode, nextWindow)).isTrue();
  }

  @Test
  void totpRejectsBadCode() {
    String code = Totp.generate(SECRET, NOW);
    assertThat(Totp.verify(SECRET, "000000", NOW)).isFalse();
    assertThat(Totp.verify(SECRET, code.substring(0, 5), NOW)).isFalse();
    assertThat(Totp.verify(SECRET, null, NOW)).isFalse();
    assertThat(Totp.verify(SECRET, "abcdef", NOW)).isFalse();
  }

  @Test
  void base32RoundTripAndCaseInsensitiveDecode() {
    byte[] data = new byte[] {0x00, 0x11, 0x22, (byte) 0xff, 0x7f};
    String encoded = Base32.encode(data);
    assertThat(Base32.decode(encoded)).isEqualTo(data);
    assertThat(Base32.decode(encoded.toLowerCase())).isEqualTo(data);
    assertThat(Base32.decode(encoded + "==")).isEqualTo(data);
  }

  @Test
  void base32EncodeWithPartialBits() {
    assertThat(Base32.encode(new byte[] {0x01})).isNotEmpty();
  }

  @Test
  void base32RejectsOutOfRangeCharacter() {
    assertThatThrownBy(() -> Base32.decode("AAAA\u00ffAAA"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void base32RejectsInvalidCharacter() {
    assertThatThrownBy(() -> Base32.decode("AAAA!AAA"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Base32");
  }

  @Test
  void backupCodesGenerateEightUnique() {
    SecureRandom random = new SecureRandom(new byte[] {1, 2, 3, 4, 5});
    List<String> codes = BackupCodes.generate(random);
    assertThat(codes).hasSize(BackupCodes.COUNT);
    assertThat(codes).allMatch(BackupCodes::looksLikeBackupCode);
    Set<String> unique = codes.stream().collect(Collectors.toSet());
    assertThat(unique).hasSize(BackupCodes.COUNT);
  }

  @Test
  void backupCodesNormaliseAndLooksLike() {
    assertThat(BackupCodes.normalise(" abcd-1234 ")).isEqualTo("ABCD-1234");
    assertThat(BackupCodes.normalise(null)).isNull();
    assertThat(BackupCodes.looksLikeBackupCode("ABCD-1234")).isTrue();
    assertThat(BackupCodes.looksLikeBackupCode("abcd-1234")).isTrue();
    assertThat(BackupCodes.looksLikeBackupCode("ABCD1234")).isFalse();
    assertThat(BackupCodes.looksLikeBackupCode(null)).isFalse();
  }

  @Test
  void backupCodesToStoredRows() {
    List<String> plain = List.of("ABCD-1234", "WXYZ-9876");
    List<Map<String, Object>> rows = BackupCodes.toStoredRows(plain, value -> sha256(value));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("hash")).isEqualTo(sha256("ABCD-1234"));
    assertThat(rows.get(0).get("used_at")).isNull();
    assertThat(rows.get(1).get("hash")).isEqualTo(sha256("WXYZ-9876"));
  }

  @Test
  void hotpFailureWrapped() {
    assertThatThrownBy(
            () ->
                Totp.hotp(
                    SECRET,
                    0L,
                    () -> {
                      throw new java.security.GeneralSecurityException("test");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOTP failed");
  }

  private static String sha256(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
