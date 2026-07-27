package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

class PharmacyStaffAuthIT extends AbstractApiIT {

  private static final String PASSWORD = "Passw0rd!";
  private static final String PIN = "1234";
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

  private static final UUID PHARMACY_1_ID = UUID.fromString("aaaaaaaa-0001-0001-0001-000000000001");
  private static final UUID PHARMACY_2_ID = UUID.fromString("aaaaaaaa-0001-0001-0001-000000000002");
  private static final UUID STAFF_1_ID = UUID.fromString("bbbbbbbb-0001-0001-0001-000000000001");
  private static final UUID STAFF_2_ID = UUID.fromString("bbbbbbbb-0001-0001-0001-000000000002");

  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String STAFF_ROLE_ID = "00000000-0000-0000-0001-000000000004";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void seedData() {
    // Avoid IP rate-limit bleed across tests (client IP is remoteAddr, not XFF).
    var keys = redis.keys("pharmacy:ip:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    keys = redis.keys("pharmacy:switch:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }

    // Clean up in FK order
    jdbc.update(
        "DELETE FROM auth_login_audit WHERE identifier IN ('priya@pharmacy.in',"
            + " 'kavya@pharmacy.in', '"
            + STAFF_1_ID
            + "', '"
            + STAFF_2_ID
            + "')");
    jdbc.update("DELETE FROM sessions WHERE user_id IN (?, ?)", STAFF_1_ID, STAFF_2_ID);
    jdbc.update(
        "DELETE FROM pharmacy_staff_assignment WHERE staff_id IN (?, ?)", STAFF_1_ID, STAFF_2_ID);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id IN (?, ?)", STAFF_1_ID, STAFF_2_ID);
    jdbc.update("DELETE FROM pharmacies WHERE id IN (?, ?)", PHARMACY_1_ID, PHARMACY_2_ID);

    String passHash = ENCODER.encode(PASSWORD);
    String pinHash = ENCODER.encode(PIN);

    // Pharmacies
    jdbc.update(
        "INSERT INTO pharmacies (id, name, city, subscription_plan, code, created_at, updated_at)"
            + " VALUES (?, 'Sri Rama Medicals', 'Bengaluru', 'GROWTH', 'PHM-STA1', NOW(), NOW())",
        PHARMACY_1_ID);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, city, subscription_plan, code, created_at, updated_at)"
            + " VALUES (?, 'Rama Pharmacy Koramangala', 'Bengaluru', 'STARTER', 'PHM-STA2', NOW(),"
            + " NOW())",
        PHARMACY_2_ID);

    // Staff 1: owner in pharmacy1, staff in pharmacy2, has POS PIN
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, pos_pin_hash,"
            + " status, failed_login_attempts, created_at, updated_at) VALUES"
            + " (?, 'Priya Sharma', 'priya@pharmacy.in', ?, ?, 'ACTIVE', 0, NOW(), NOW())",
        STAFF_1_ID,
        passHash,
        pinHash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id,"
            + " is_active, joined_at) VALUES (?, ?, ?, ?::uuid, true, '2026-01-01T00:00:00Z')",
        UUID.randomUUID(),
        STAFF_1_ID,
        PHARMACY_1_ID,
        OWNER_ROLE_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id,"
            + " is_active, joined_at) VALUES (?, ?, ?, ?::uuid, true, '2026-02-01T00:00:00Z')",
        UUID.randomUUID(),
        STAFF_1_ID,
        PHARMACY_2_ID,
        STAFF_ROLE_ID);

    // Staff 2: cashier in pharmacy1 only, no POS PIN
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, phone, password_hash,"
            + " status, failed_login_attempts, created_at, updated_at) VALUES"
            + " (?, 'Kavya Nair', '+919876543210', ?, 'ACTIVE', 0, NOW(), NOW())",
        STAFF_2_ID,
        passHash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id,"
            + " is_active, joined_at) VALUES (?, ?, ?, ?::uuid, true, '2026-01-15T00:00:00Z')",
        UUID.randomUUID(),
        STAFF_2_ID,
        PHARMACY_1_ID,
        STAFF_ROLE_ID);
  }

  @Test
  void loginWithEmailHappyPath() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "priya@pharmacy.in", "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    assertThat(body.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data.get("access_token")).isInstanceOf(String.class);
    assertThat(data.get("refresh_token")).isInstanceOf(String.class);
    assertThat(data.get("token_type")).isEqualTo("Bearer");
    @SuppressWarnings("unchecked")
    Map<String, Object> activePharmacy = (Map<String, Object>) data.get("active_pharmacy");
    assertThat(activePharmacy.get("name")).isEqualTo("Sri Rama Medicals");
    @SuppressWarnings("unchecked")
    java.util.List<?> pharmacies = (java.util.List<?>) data.get("pharmacies");
    assertThat(pharmacies).hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> staff = (Map<String, Object>) data.get("staff");
    assertThat(staff.get("mfa_enabled")).isEqualTo(false);
  }

  @Test
  void loginWithPhoneHappyPath() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "+91 9876543210", "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
    assertThat(data.get("access_token")).isInstanceOf(String.class);
  }

  @Test
  void switchPharmacyHappyPath() {
    // First login
    ResponseEntity<Map> loginResp =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "priya@pharmacy.in", "password", PASSWORD));
    @SuppressWarnings("unchecked")
    Map<String, Object> loginData =
        (Map<String, Object>) Objects.requireNonNull(loginResp.getBody()).get("data");
    String accessToken = (String) loginData.get("access_token");

    // Switch to pharmacy 2
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken);
    ResponseEntity<Map> switchResp =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/switch-pharmacy",
            new HttpEntity<>(Map.of("pharmacy_id", PHARMACY_2_ID.toString()), headers),
            Map.class);

    assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> switchData =
        (Map<String, Object>) Objects.requireNonNull(switchResp.getBody()).get("data");
    assertThat(switchData.get("access_token")).isInstanceOf(String.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> ap = (Map<String, Object>) switchData.get("active_pharmacy");
    assertThat(ap.get("name")).isEqualTo("Rama Pharmacy Koramangala");
    assertThat(switchData.get("role_in_pharmacy")).isEqualTo("manager");
  }

  @Test
  void switchPharmacyForbiddenWhenNotAssigned() {
    // Login as staff2 (only assigned to pharmacy1)
    ResponseEntity<Map> loginResp =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "+919876543210", "password", PASSWORD));
    @SuppressWarnings("unchecked")
    Map<String, Object> loginData =
        (Map<String, Object>) Objects.requireNonNull(loginResp.getBody()).get("data");
    String accessToken = (String) loginData.get("access_token");

    // Try to switch to pharmacy2 (not assigned)
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken);
    ResponseEntity<Map> switchResp =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/switch-pharmacy",
            new HttpEntity<>(Map.of("pharmacy_id", PHARMACY_2_ID.toString()), headers),
            Map.class);

    assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(switchResp.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("FORBIDDEN");
  }

  @Test
  void posPinHappyPath() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/pos-pin",
            Map.of(
                "pharmacy_id", PHARMACY_1_ID.toString(),
                "staff_id", STAFF_1_ID.toString(),
                "pin", PIN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
    assertThat(data.get("access_token")).isInstanceOf(String.class);
    assertThat(data.get("token_scope")).isEqualTo("pos");
    assertThat(data.get("access_token_expires_in")).isEqualTo(14400);
  }

  @Test
  void posPinNotSetReturns403() {
    // Staff 2 has no POS pin
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/pos-pin",
            Map.of(
                "pharmacy_id", PHARMACY_1_ID.toString(),
                "staff_id", STAFF_2_ID.toString(),
                "pin", PIN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("POS_PIN_NOT_SET");
  }

  @Test
  void posTokenBlockedOnNonPosEndpoint() {
    // Get POS token
    ResponseEntity<Map> pinResp =
        post(
            "/api/v1/auth/pharmacy/pos-pin",
            Map.of(
                "pharmacy_id", PHARMACY_1_ID.toString(),
                "staff_id", STAFF_1_ID.toString(),
                "pin", PIN));
    @SuppressWarnings("unchecked")
    Map<String, Object> pinData =
        (Map<String, Object>) Objects.requireNonNull(pinResp.getBody()).get("data");
    String posToken = (String) pinData.get("access_token");

    // Try to use POS token on switch-pharmacy (non-POS endpoint)
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(posToken);
    ResponseEntity<Map> switchResp =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/switch-pharmacy",
            new HttpEntity<>(Map.of("pharmacy_id", PHARMACY_2_ID.toString()), headers),
            Map.class);

    assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(switchResp.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("POS_TOKEN_RESTRICTED");
  }

  @Test
  void accountLockedAfterFiveFailedAttempts() {
    for (int i = 0; i < 5; i++) {
      post(
          "/api/v1/auth/pharmacy/login",
          Map.of("identifier", "priya@pharmacy.in", "password", "WrongPass1!"));
    }

    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "priya@pharmacy.in", "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = Objects.requireNonNull(response.getBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error.get("code")).isEqualTo("ACCOUNT_LOCKED");
    assertThat(error.get("details")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) error.get("details");
    assertThat(details.get("unlock_at")).isNotNull();
  }

  @Test
  void invalidCredentialsReturns401() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "priya@pharmacy.in", "password", "WrongPass1!"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void staffNotFoundReturns404() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/login",
            Map.of("identifier", "nobody@pharmacy.in", "password", PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("STAFF_NOT_FOUND");
  }

  @Test
  void switchPharmacyUnauthorizedWithoutToken() {
    ResponseEntity<Map> response =
        post(
            "/api/v1/auth/pharmacy/switch-pharmacy",
            Map.of("pharmacy_id", PHARMACY_2_ID.toString()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = Objects.requireNonNull(response.getBody());
    assertThat(body.get("success")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error.get("code")).isEqualTo("UNAUTHORIZED");
  }

  private ResponseEntity<Map> post(String path, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), Map.class);
  }
}
