package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.BackupCodes;
import com.nammamedmate.auth.domain.Base32;
import com.nammamedmate.auth.domain.Totp;
import com.nammamedmate.security.AesGcmCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminAuthIT extends AbstractApiIT {

  private static final String PASSWORD = "Passw0rd!";
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

  private static final UUID SUPER_ID = UUID.fromString("cccccccc-0003-0003-0003-000000000001");
  private static final UUID OPS_ID = UUID.fromString("cccccccc-0003-0003-0003-000000000002");
  private static final UUID SUPER_NO_MFA_ID =
      UUID.fromString("cccccccc-0003-0003-0003-000000000003");

  private static final String SUPER_EMAIL = "super@test.in";
  private static final String OPS_EMAIL = "ops@test.in";
  private static final String SUPER_NO_MFA_EMAIL = "super-nomfa@test.in";
  private static final String KNOWN_BACKUP = "ABCD-1234";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  private String plaintextTotpSecret;
  private String encryptedTotpSecret;
  private String backupCodesJson;

  @BeforeEach
  void seedData() {
    flushRedis("admin:ip:*");
    flushRedis("admin:user:*");
    flushRedis("auth:revoked:*");

    jdbc.update(
        "DELETE FROM admin_auth_events WHERE admin_id IN (?, ?, ?)",
        SUPER_ID,
        OPS_ID,
        SUPER_NO_MFA_ID);
    jdbc.update(
        "DELETE FROM sessions WHERE user_id IN (?, ?, ?)", SUPER_ID, OPS_ID, SUPER_NO_MFA_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id IN (?, ?, ?)", SUPER_ID, OPS_ID, SUPER_NO_MFA_ID);

    AesGcmCipher cipher =
        AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    // Fixed 20-byte secret so TOTP codes are reproducible across the IT class.
    byte[] secretBytes = new byte[20];
    for (int i = 0; i < secretBytes.length; i++) {
      secretBytes[i] = (byte) (i + 1);
    }
    plaintextTotpSecret = Base32.encode(secretBytes);
    encryptedTotpSecret = cipher.encrypt(plaintextTotpSecret);

    backupCodesJson =
        buildBackupCodesJson(
            List.of(
                KNOWN_BACKUP,
                "WXYZ-9876",
                "PQRS-5678",
                "LMNO-4321",
                "EFGH-8765",
                "IJKL-2468",
                "MNOP-1357",
                "QRST-9753"));

    String passHash = ENCODER.encode(PASSWORD);

    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " totp_secret, backup_codes, failed_login_attempts, created_at, updated_at)"
            + " VALUES (?, 'Super Admin', ?, ?, 'admin_super', 'ACTIVE', true, ?, ?::jsonb, 0,"
            + " NOW(), NOW())",
        SUPER_ID,
        SUPER_EMAIL,
        passHash,
        encryptedTotpSecret,
        backupCodesJson);

    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Ops Admin', ?, ?,"
            + " 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        passHash);

    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Super No MFA', ?, ?,"
            + " 'admin_super', 'ACTIVE', false, 0, NOW(), NOW())",
        SUPER_NO_MFA_ID,
        SUPER_NO_MFA_EMAIL,
        passHash);
  }

  @Test
  void superLoginRequiresMfaChallenge() {
    ResponseEntity<Map> response =
        post("/api/v1/auth/admin/login", Map.of("email", SUPER_EMAIL, "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
    assertThat(data.get("mfa_required")).isEqualTo(true);
    assertThat(data.get("mfa_challenge_token")).isInstanceOf(String.class);
    assertThat(data.get("access_token")).isNull();
    assertThat(data.get("mfa_challenge_expires_in")).isEqualTo(300);
  }

  @Test
  void verifyMfaWithTotpIssuesTokens() {
    String challenge = loginChallenge(SUPER_EMAIL);
    String code = Totp.generate(Base32.decode(plaintextTotpSecret), Instant.now());

    ResponseEntity<Map> response =
        postAuth(
            "/api/v1/auth/admin/verify-mfa",
            Map.of("mfa_challenge_token", challenge, "code", code),
            challenge);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
    assertThat(data.get("access_token")).isInstanceOf(String.class);
    assertThat(data.get("refresh_token")).isInstanceOf(String.class);
    assertThat(data.get("access_token_expires_in")).isEqualTo(900);
    assertThat(data.get("refresh_token_expires_in")).isEqualTo(28800);
    assertThat(data.get("used_backup_code")).isEqualTo(false);

    Integer events =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_auth_events WHERE admin_id = ? AND event_type IN"
                + " ('MFA_SUCCESS', 'LOGIN_SUCCESS')",
            Integer.class,
            SUPER_ID);
    assertThat(events).isGreaterThanOrEqualTo(1);
  }

  @Test
  void opsLoginAndSetupMfa() {
    ResponseEntity<Map> login =
        post("/api/v1/auth/admin/login", Map.of("email", OPS_EMAIL, "password", PASSWORD));
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> loginData =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    assertThat(loginData.get("mfa_required")).isEqualTo(false);
    String accessToken = (String) loginData.get("access_token");

    ResponseEntity<Map> setup1 = postAuth("/api/v1/auth/admin/setup-mfa", Map.of(), accessToken);
    assertThat(setup1.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> setupData1 =
        (Map<String, Object>) Objects.requireNonNull(setup1.getBody()).get("data");
    @SuppressWarnings("unchecked")
    List<String> codes1 = (List<String>) setupData1.get("backup_codes");
    assertThat(codes1).hasSize(8);
    assertThat(setupData1.get("totp_uri")).asString().contains("secret=");

    Boolean mfaEnabled =
        jdbc.queryForObject(
            "SELECT mfa_enabled FROM admin_staff WHERE id = ?", Boolean.class, OPS_ID);
    assertThat(mfaEnabled).isFalse();

    ResponseEntity<Map> setup2 = postAuth("/api/v1/auth/admin/setup-mfa", Map.of(), accessToken);
    @SuppressWarnings("unchecked")
    Map<String, Object> setupData2 =
        (Map<String, Object>) Objects.requireNonNull(setup2.getBody()).get("data");
    assertThat(setupData2.get("totp_secret")).isNotEqualTo(setupData1.get("totp_secret"));
    mfaEnabled =
        jdbc.queryForObject(
            "SELECT mfa_enabled FROM admin_staff WHERE id = ?", Boolean.class, OPS_ID);
    assertThat(mfaEnabled).isFalse();
  }

  @Test
  void verifyMfaWithBackupCode() {
    String challenge = loginChallenge(SUPER_EMAIL);

    ResponseEntity<Map> response =
        postAuth(
            "/api/v1/auth/admin/verify-mfa",
            Map.of("mfa_challenge_token", challenge, "code", KNOWN_BACKUP),
            challenge);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
    assertThat(data.get("used_backup_code")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> admin = (Map<String, Object>) data.get("admin");
    assertThat(admin.get("backup_codes_remaining")).isEqualTo(7);
  }

  @Test
  void accountLockedAfterFiveFailedLogins() {
    for (int i = 0; i < 5; i++) {
      post("/api/v1/auth/admin/login", Map.of("email", OPS_EMAIL, "password", "WrongPass1!"));
    }

    ResponseEntity<Map> response =
        post("/api/v1/auth/admin/login", Map.of("email", OPS_EMAIL, "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("ACCOUNT_LOCKED");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) error.get("details");
    assertThat(details.get("unlock_at")).isNotNull();

    Integer eventsWithIp =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_auth_events WHERE admin_id = ? AND ip_address IS NOT NULL",
            Integer.class,
            OPS_ID);
    assertThat(eventsWithIp).isGreaterThan(0);
  }

  @Test
  void superWithoutMfaRequiresEnrollment() {
    ResponseEntity<Map> response =
        post("/api/v1/auth/admin/login", Map.of("email", SUPER_NO_MFA_EMAIL, "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("MFA_ENROLLMENT_REQUIRED");
  }

  @Test
  void invalidChallengeTokenReturnsUnauthorized() {
    ResponseEntity<Map> response =
        postAuth(
            "/api/v1/auth/admin/verify-mfa",
            Map.of("mfa_challenge_token", "not-a-valid-jwt", "code", "123456"),
            "not-a-valid-jwt");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void verifyMfaWithoutBearerReturnsUnauthorized() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/admin/verify-mfa",
            Map.of("mfa_challenge_token", "not-a-valid-jwt", "code", "123456"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void adminNotFound() {
    ResponseEntity<Map> response =
        post("/api/v1/auth/admin/login", Map.of("email", "nobody@test.in", "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("ADMIN_NOT_FOUND");
  }

  @Test
  void invalidCredentials() {
    ResponseEntity<Map> response =
        post("/api/v1/auth/admin/login", Map.of("email", OPS_EMAIL, "password", "WrongPass1!"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void completeInviteActivatesStaffAndRejectsReuse() {
    UUID invitedId = UUID.fromString("cccccccc-0003-0003-0003-000000000099");
    String email = "invited-it@test.in";
    String token = "invite-it-token-001";
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", invitedId);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", invitedId);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", invitedId, email);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " invite_token_hash, invite_expires_at, failed_login_attempts, created_at, updated_at)"
            + " VALUES (?, 'Invited Ops', ?, NULL, 'admin_operations', 'INVITED', false, ?,"
            + " NOW() + INTERVAL '1 day', 0, NOW(), NOW())",
        invitedId,
        email,
        sha256(token));

    ResponseEntity<Map> completed =
        post(
            "/api/v1/auth/admin/complete-invite",
            Map.of("invite_token", token, "password", PASSWORD));
    assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(completed.getBody()).get("data");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("email")).isEqualTo(email);

    String status =
        jdbc.queryForObject("SELECT status FROM admin_staff WHERE id = ?", String.class, invitedId);
    assertThat(status).isEqualTo("ACTIVE");

    ResponseEntity<Map> reused =
        post(
            "/api/v1/auth/admin/complete-invite",
            Map.of("invite_token", token, "password", PASSWORD));
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> reusedErr =
        (Map<String, Object>) Objects.requireNonNull(reused.getBody()).get("error");
    assertThat(reusedErr.get("code")).isEqualTo("INVITE_INVALID");

    ResponseEntity<Map> login =
        post("/api/v1/auth/admin/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void setupMfaUnauthorizedWithoutToken() {
    ResponseEntity<Map> response = post("/api/v1/auth/admin/setup-mfa", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("UNAUTHORIZED");
  }

  private String loginChallenge(String email) {
    ResponseEntity<Map> login =
        post("/api/v1/auth/admin/login", Map.of("email", email, "password", PASSWORD));
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    return (String) data.get("mfa_challenge_token");
  }

  private void flushRedis(String pattern) {
    var keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  private static String buildBackupCodesJson(List<String> codes) {
    String rows =
        codes.stream()
            .map(
                code ->
                    "{\"hash\":\"" + sha256(BackupCodes.normalise(code)) + "\",\"used_at\":null}")
            .collect(Collectors.joining(","));
    return "[" + rows + "]";
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

  private ResponseEntity<Map> post(String path, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), Map.class);
  }

  private ResponseEntity<Map> postAuth(String path, Map<String, ?> body, String accessToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken);
    return rest.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), Map.class);
  }
}
