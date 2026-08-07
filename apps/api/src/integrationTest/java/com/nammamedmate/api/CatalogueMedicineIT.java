package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CatalogueMedicineIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");
  private static final UUID COMPLIANCE_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeeeeeee");
  private static final String OPS_EMAIL = "catalogue-ops@test.in";
  private static final String COMPLIANCE_EMAIL = "catalogue-compliance@test.in";
  private static final String PASSWORD = "CatalogueAdmin1!";
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seedAdmin() {
    jdbc.update("DELETE FROM audit_log WHERE actor_id IN (?, ?)", OPS_ID, COMPLIANCE_ID);
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM medicine_ban_job");
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping");
    jdbc.update("DELETE FROM medicine_master");
    jdbc.update("DELETE FROM sessions WHERE user_id IN (?, ?)", OPS_ID, COMPLIANCE_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id IN (?, ?)", OPS_ID, COMPLIANCE_ID);
    jdbc.update(
        "DELETE FROM admin_staff WHERE id IN (?, ?) OR email IN (?, ?)",
        OPS_ID,
        COMPLIANCE_ID,
        OPS_EMAIL,
        COMPLIANCE_EMAIL);
    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Catalogue Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Catalogue Compliance',"
            + " ?, ?, 'admin_compliance', 'ACTIVE', false, 0, NOW(), NOW())",
        COMPLIANCE_ID,
        COMPLIANCE_EMAIL,
        hash);
  }

  @Test
  void createListSummaryGetPatchBanUnban() {
    String token = adminLogin(OPS_EMAIL);
    String complianceToken = adminLogin(COMPLIANCE_EMAIL);

    Map<String, Object> createBody = new HashMap<>();
    createBody.put("name", "Augmentin 625 Tablet");
    createBody.put("salt_composition", "Amoxicillin (500mg) + Clavulanic Acid (125mg)");
    createBody.put("manufacturer", "GSK India");
    createBody.put("category_id", CATEGORY.toString());
    createBody.put("form", "TABLET");
    createBody.put("pack_size", 10);
    createBody.put("pack_unit", "TABLET");
    createBody.put("schedule", "H");
    createBody.put("hsn_code", "30041090");
    createBody.put("gst_pct", 12);
    createBody.put("mrp", 218.50);
    createBody.put("is_rx_only", false);
    createBody.put("description", "Combo antibiotic");
    createBody.put("monthly_demand", 999);

    ResponseEntity<Map> created =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue",
            HttpMethod.POST,
            bearer(token, createBody),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> createdData = data(created);
    assertThat(createdData).containsEntry("is_rx_only", true).containsEntry("monthly_demand", 0);
    String medicineId = String.valueOf(createdData.get("medicine_id"));

    Map<String, Object> dupBody = new HashMap<>();
    dupBody.put("name", "Other Brand");
    dupBody.put("salt_composition", "Amoxicillin (500mg) + Clavulanic Acid (125mg)");
    dupBody.put("manufacturer", "GSK India");
    dupBody.put("category_id", CATEGORY.toString());
    dupBody.put("form", "TABLET");
    dupBody.put("pack_size", 10);
    dupBody.put("pack_unit", "TABLET");
    dupBody.put("schedule", "H");
    dupBody.put("hsn_code", "30041090");
    dupBody.put("gst_pct", 12);
    dupBody.put("mrp", 200);
    dupBody.put("is_rx_only", true);

    ResponseEntity<Map> duplicate =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue",
            HttpMethod.POST,
            bearer(token, dupBody),
            Map.class);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(duplicate)).isEqualTo("DUPLICATE_MEDICINE");

    ResponseEntity<Map> list =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue?page=1&limit=20",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(list).get("medicines")).asList().isNotEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> meta =
        (Map<String, Object>) Objects.requireNonNull(list.getBody()).get("meta");
    assertThat(meta).containsKeys("page", "limit", "total", "has_next");

    ResponseEntity<Map> summary =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/summary",
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(summary)).containsKey("total_skus");

    ResponseEntity<Map> detail =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId,
            HttpMethod.GET,
            bearer(token, null),
            Map.class);
    assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(detail))
        .containsKeys("stocking_pharmacies", "substitutes", "demand_stats", "mrp_ceiling");

    ResponseEntity<Map> badGst =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId,
            HttpMethod.PATCH,
            bearer(token, Map.of("gst_pct", 7)),
            Map.class);
    assertThat(badGst.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(badGst)).isEqualTo("INVALID_GST_RATE");

    ResponseEntity<Map> patched =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId,
            HttpMethod.PATCH,
            bearer(token, Map.of("description", "Updated", "monthly_demand", 42)),
            Map.class);
    assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> banned =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/ban",
            HttpMethod.POST,
            bearer(complianceToken, Map.of("reason", "CDSCO notification")),
            Map.class);
    assertThat(banned.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(banned)).containsEntry("is_banned", true);

    ResponseEntity<Map> bannedList =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue?is_banned=true",
            HttpMethod.GET,
            bearer(complianceToken, null),
            Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bannedMeds =
        (List<Map<String, Object>>) data(bannedList).get("medicines");
    assertThat(bannedMeds).isNotEmpty();
    assertThat(bannedMeds.get(0)).containsKey("ban_reason");

    ResponseEntity<Map> categories =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/categories", Map.class);
    assertThat(categories.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> unbanned =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/unban",
            HttpMethod.POST,
            bearer(complianceToken, Map.of("reason", "Ban lifted")),
            Map.class);
    assertThat(unbanned.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(unbanned)).containsEntry("is_banned", false);
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", email, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode())
        .as("admin login body=%s", login.getBody())
        .isEqualTo(HttpStatus.OK);
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
