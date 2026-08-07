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

class CatalogueMappingIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-aaaaaaaaaaaa");
  private static final UUID COMPLIANCE_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-bbbbbbbbbbbb");
  private static final UUID PHARMACY_ID = UUID.fromString("aaaaaaaa-0009-0009-0009-000000000001");
  private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-0009-0009-0009-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "catalogue-map-ops@test.in";
  private static final String COMPLIANCE_EMAIL = "catalogue-map-compliance@test.in";
  private static final String OWNER_EMAIL = "catalogue-map-owner@test.in";
  private static final String PASSWORD = "CatalogueMap1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM medicine_ban_job");
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping");
    jdbc.update("DELETE FROM medicine_master");
    jdbc.update("DELETE FROM sessions WHERE user_id IN (?, ?, ?)", OPS_ID, COMPLIANCE_ID, STAFF_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id IN (?, ?)", OPS_ID, COMPLIANCE_ID);
    jdbc.update(
        "DELETE FROM admin_staff WHERE id IN (?, ?) OR email IN (?, ?)",
        OPS_ID,
        COMPLIANCE_ID,
        OPS_EMAIL,
        COMPLIANCE_EMAIL);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF_ID);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF_ID, OWNER_EMAIL);
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PHARMACY_ID);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Map Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Map Compliance', ?,"
            + " ?, 'admin_compliance', 'ACTIVE', false, 0, NOW(), NOW())",
        COMPLIANCE_ID,
        COMPLIANCE_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " created_at, updated_at) VALUES (?, 'Map Pharmacy', 'Map Pharmacy', 'Bengaluru',"
            + " 'GROWTH', 'PHM-MAP1', 'ACTIVE', NOW(), NOW())",
        PHARMACY_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Map Owner', ?, ?,"
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
  void createListPatchAdminBulkAndBanHides() {
    String opsToken = adminLogin(OPS_EMAIL);
    String complianceToken = adminLogin(COMPLIANCE_EMAIL);
    String pharmacyToken = pharmacyLogin();

    String medicineId = createMedicine(opsToken, "H", 218.50);

    ResponseEntity<Map> aboveMrp =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    220.00,
                    "stock_quantity",
                    48)),
            Map.class);
    assertThat(aboveMrp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(aboveMrp)).isEqualTo("PRICE_ABOVE_MRP");

    ResponseEntity<Map> created =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    215.00,
                    "stock_quantity",
                    48)),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String mappingId = String.valueOf(data(created).get("mapping_id"));

    ResponseEntity<Map> dup =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    200.00,
                    "stock_quantity",
                    1)),
            Map.class);
    assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(dup)).isEqualTo("MAPPING_ALREADY_EXISTS");

    ResponseEntity<Map> list =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.GET,
            bearer(pharmacyToken, null),
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<?> mappings = (List<?>) data(list).get("mappings");
    assertThat(mappings).hasSize(1);

    ResponseEntity<Map> patched =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping/" + mappingId,
            HttpMethod.PATCH,
            bearer(pharmacyToken, Map.of("is_visible", false)),
            Map.class);
    assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(patched)).containsEntry("is_visible", false);

    ResponseEntity<Map> adminList =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/pharmacy-mappings",
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(adminList).get("total_pharmacies_stocking")).isEqualTo(1);

    ResponseEntity<Map> bulk =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/bulk-map",
            HttpMethod.POST,
            bearer(
                opsToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_ids",
                    List.of(PHARMACY_ID),
                    "auto_price_from_mrp",
                    true,
                    "initial_stock_quantity",
                    0)),
            Map.class);
    assertThat(bulk.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(data(bulk)).containsEntry("status", "QUEUED");

    ResponseEntity<Map> banned =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue/" + medicineId + "/ban",
            HttpMethod.POST,
            bearer(complianceToken, Map.of("reason", "CDSCO ban for mapping IT")),
            Map.class);
    assertThat(banned.getStatusCode()).isEqualTo(HttpStatus.OK);
    Integer hidden =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacy_catalogue_mapping WHERE master_medicine_id = ?::uuid"
                + " AND is_visible = FALSE",
            Integer.class,
            UUID.fromString(medicineId));
    assertThat(hidden).isEqualTo(1);

    String scheduleX = createMedicine(opsToken, "X", 50.00);
    ResponseEntity<Map> scheduleXMap =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id", scheduleX, "pharmacy_price", 40.00, "stock_quantity", 1)),
            Map.class);
    assertThat(scheduleXMap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(scheduleXMap)).isEqualTo("SCHEDULE_X_NOT_AVAILABLE_ONLINE");
  }

  private String createMedicine(String adminToken, String schedule, double mrp) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Map Med " + schedule + " " + UUID.randomUUID());
    body.put("salt_composition", "Salt " + UUID.randomUUID());
    body.put("manufacturer", "Maker");
    body.put("category_id", CATEGORY.toString());
    body.put("form", "TABLET");
    body.put("pack_size", 10);
    body.put("pack_unit", "TABLET");
    body.put("schedule", schedule);
    body.put("hsn_code", "30041090");
    body.put("gst_pct", 12);
    body.put("mrp", mrp);
    body.put("is_rx_only", false);
    ResponseEntity<Map> created =
        rest.exchange(
            baseUrl() + "/api/v1/admin/catalogue",
            HttpMethod.POST,
            bearer(adminToken, body),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return String.valueOf(data(created).get("medicine_id"));
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
