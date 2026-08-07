package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.Base32;
import com.nammamedmate.auth.domain.Totp;
import com.nammamedmate.security.AesGcmCipher;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CataloguePriceCeilingIT extends AbstractApiIT {

  private static final UUID SUPER_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-cccccccccccc");
  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-dddddddddddd");
  private static final UUID COMPLIANCE_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeeeeeee");
  private static final UUID PHARMACY_ID = UUID.fromString("aaaaaaaa-000a-000a-000a-000000000001");
  private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-000a-000a-000a-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String SUPER_EMAIL = "catalogue-ceiling-super@test.in";
  private static final String OPS_EMAIL = "catalogue-ceiling-ops@test.in";
  private static final String COMPLIANCE_EMAIL = "catalogue-ceiling-compliance@test.in";
  private static final String OWNER_EMAIL = "catalogue-ceiling-owner@test.in";
  private static final String PASSWORD = "CatalogueCeil1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  private String plaintextTotpSecret;

  @BeforeEach
  void seed() {
    var keys = redis.keys("admin:ip:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }

    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM medicine_ban_job");
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping");
    jdbc.update("DELETE FROM medicine_master");
    jdbc.update(
        "DELETE FROM sessions WHERE user_id IN (?, ?, ?, ?)",
        SUPER_ID,
        OPS_ID,
        COMPLIANCE_ID,
        STAFF_ID);
    jdbc.update(
        "DELETE FROM admin_auth_events WHERE admin_id IN (?, ?, ?)",
        SUPER_ID,
        OPS_ID,
        COMPLIANCE_ID);
    jdbc.update(
        "DELETE FROM admin_staff WHERE id IN (?, ?, ?) OR email IN (?, ?, ?)",
        SUPER_ID,
        OPS_ID,
        COMPLIANCE_ID,
        SUPER_EMAIL,
        OPS_EMAIL,
        COMPLIANCE_EMAIL);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF_ID);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF_ID, OWNER_EMAIL);
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PHARMACY_ID);

    AesGcmCipher cipher =
        AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    byte[] secretBytes = new byte[20];
    for (int i = 0; i < secretBytes.length; i++) {
      secretBytes[i] = (byte) (i + 11);
    }
    plaintextTotpSecret = Base32.encode(secretBytes);
    String encryptedTotpSecret = cipher.encrypt(plaintextTotpSecret);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " totp_secret, failed_login_attempts, created_at, updated_at) VALUES (?, 'Ceil"
            + " Super', ?, ?, 'admin_super', 'ACTIVE', true, ?, 0, NOW(), NOW())",
        SUPER_ID,
        SUPER_EMAIL,
        hash,
        encryptedTotpSecret);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Ceil Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Ceil Compliance', ?,"
            + " ?, 'admin_compliance', 'ACTIVE', false, 0, NOW(), NOW())",
        COMPLIANCE_ID,
        COMPLIANCE_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " created_at, updated_at) VALUES (?, 'Ceil Pharmacy', 'Ceil Pharmacy', 'Bengaluru',"
            + " 'GROWTH', 'PHM-CEIL', 'ACTIVE', NOW(), NOW())",
        PHARMACY_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Ceil Owner', ?, ?,"
            + " 'ACTIVE', 0, NOW(), NOW())",
        STAFF_ID,
        OWNER_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF_ID,
        PHARMACY_ID,
        OWNER_ROLE_ID);
  }

  @Test
  void setListNotifyRateLimitAndRemove() {
    String superToken = superLoginWithMfa();
    String opsToken = adminLogin(OPS_EMAIL);
    String complianceToken = adminLogin(COMPLIANCE_EMAIL);
    String pharmacyToken = pharmacyLogin();

    String medicineId = createMedicine(opsToken, 85.00);

    ResponseEntity<Map> map =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    80.00,
                    "stock_quantity",
                    10)),
            Map.class);
    assertThat(map.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> aboveMrp =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/price-ceiling",
            HttpMethod.POST,
            bearer(superToken, Map.of("ceiling_price", 90.00, "reason", "too high")),
            Map.class);
    assertThat(aboveMrp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(aboveMrp)).isEqualTo("CEILING_ABOVE_MRP");

    ResponseEntity<Map> forbidden =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/price-ceiling",
            HttpMethod.POST,
            bearer(opsToken, Map.of("ceiling_price", 72.00, "reason", "NLEM")),
            Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> set =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/price-ceiling",
            HttpMethod.POST,
            bearer(
                superToken,
                Map.of(
                    "ceiling_price",
                    72.00,
                    "effective_from",
                    "2026-07-01",
                    "reason",
                    "NLEM price ceiling per NPPA")),
            Map.class);
    assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(set).get("pharmacies_above_ceiling")).isEqualTo(1);
    Long ceiling =
        jdbc.queryForObject(
            "SELECT mrp_ceiling_paise FROM medicine_master WHERE id = ?::uuid",
            Long.class,
            UUID.fromString(medicineId));
    assertThat(ceiling).isEqualTo(7200L);

    ResponseEntity<Map> ceilings =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/price-ceilings?has_violations=true",
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(ceilings.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<?> ceilingRows = (List<?>) data(ceilings).get("price_ceilings");
    assertThat(ceilingRows).hasSize(1);

    ResponseEntity<Map> violations =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/price-violations?medicine_id=" + medicineId,
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(violations.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<?> violationRows = (List<?>) data(violations).get("violations");
    assertThat(violationRows).hasSize(1);

    ResponseEntity<Map> notify =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/price-violations/notify",
            HttpMethod.POST,
            bearer(complianceToken, Map.of("medicine_id", medicineId, "message", "please lower")),
            Map.class);
    assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(notify).get("pharmacies_notified")).isEqualTo(1);
    assertThat(data(notify).get("channels")).isEqualTo(List.of("WHATSAPP", "IN_APP"));

    ResponseEntity<Map> rateLimited =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/price-violations/notify",
            HttpMethod.POST,
            bearer(complianceToken, Map.of("medicine_id", medicineId)),
            Map.class);
    assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(errorCode(rateLimited)).isEqualTo("NOTIFICATION_RATE_LIMITED");

    ResponseEntity<Map> removed =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/price-ceiling",
            HttpMethod.DELETE,
            bearer(superToken, Map.of("reason", "ceiling lifted")),
            Map.class);
    assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(removed).get("ceiling_removed")).isEqualTo(true);
    assertThat(data(removed).get("violations_resolved")).isEqualTo(1);
    Long cleared =
        jdbc.queryForObject(
            "SELECT mrp_ceiling_paise FROM medicine_master WHERE id = ?::uuid",
            Long.class,
            UUID.fromString(medicineId));
    assertThat(cleared).isNull();
  }

  private String createMedicine(String adminToken, double mrp) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Ceil Med " + UUID.randomUUID());
    body.put("salt_composition", "Salt " + UUID.randomUUID());
    body.put("manufacturer", "Maker");
    body.put("category_id", CATEGORY.toString());
    body.put("form", "CAPSULE");
    body.put("pack_size", 10);
    body.put("pack_unit", "CAPSULE");
    body.put("schedule", "H");
    body.put("hsn_code", "30041090");
    body.put("gst_pct", 12);
    body.put("mrp", mrp);
    body.put("is_rx_only", true);
    ResponseEntity<Map> created =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue",
            HttpMethod.POST,
            bearer(adminToken, body),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return String.valueOf(data(created).get("medicine_id"));
  }

  private String superLoginWithMfa() {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", SUPER_EMAIL, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String challenge = String.valueOf(data(login).get("mfa_challenge_token"));
    String code = Totp.generate(Base32.decode(plaintextTotpSecret), Instant.now());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(challenge);
    ResponseEntity<Map> verified =
        rest.exchange(
            baseUrl() + "/api/v1/auth/admin/verify-mfa",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("mfa_challenge_token", challenge, "code", code), headers),
            Map.class);
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(verified).get("access_token"));
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", email, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode())
        .as("admin login %s body=%s", email, login.getBody())
        .isEqualTo(HttpStatus.OK);
    return String.valueOf(data(login).get("access_token"));
  }

  private String pharmacyLogin() {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/login",
            json(Map.of("identifier", OWNER_EMAIL, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(login).get("access_token"));
  }

  private static HttpEntity<?> bearer(String token, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
      return new HttpEntity<>(body, headers);
    }
    return new HttpEntity<>(headers);
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("data");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    return String.valueOf(error.get("code"));
  }
}
