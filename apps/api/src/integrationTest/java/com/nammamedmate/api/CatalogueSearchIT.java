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

class CatalogueSearchIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-cccccccccccc");
  private static final UUID COMPLIANCE_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-dddddddddddd");
  private static final UUID PHARMACY_ID = UUID.fromString("aaaaaaaa-000a-000a-000a-000000000001");
  private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-000a-000a-000a-000000000001");
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "catalogue-search-ops@test.in";
  private static final String COMPLIANCE_EMAIL = "catalogue-search-compliance@test.in";
  private static final String OWNER_EMAIL = "catalogue-search-owner@test.in";
  private static final String PASSWORD = "CatalogueSearch1!";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM medicine_ban_job");
    jdbc.update("DELETE FROM price_ceiling_violation");
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping");
    jdbc.update(
        "DELETE FROM medicine_master WHERE id::text NOT LIKE 'a0000001-0000-4000-8000-%'");
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
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Search Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Search Compliance', ?,"
            + " ?, 'admin_compliance', 'ACTIVE', false, 0, NOW(), NOW())",
        COMPLIANCE_ID,
        COMPLIANCE_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " zone_id, is_online, admin_forced_offline, created_at, updated_at) VALUES (?,"
            + " 'Search Pharmacy', 'Search Pharmacy', 'Bengaluru', 'GROWTH', 'PHM-SRCH', 'ACTIVE',"
            + " ?, TRUE, FALSE, NOW(), NOW())",
        PHARMACY_ID,
        ZONE);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Search Owner', ?, ?,"
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
  void searchDetailSubstitutesAvailabilityAndPharmacySearch() {
    String opsToken = adminLogin(OPS_EMAIL);
    String complianceToken = adminLogin(COMPLIANCE_EMAIL);
    String pharmacyToken = pharmacyLogin();

    String medicineId = createMedicine(opsToken, "Augmentin 625 Tablet", "H", 218.50);
    String bannedId = createMedicine(opsToken, "Banned Med Search", "OTC", 10.00);
    String scheduleX = createMedicine(opsToken, "Morphine Search X", "X", 50.00);

    rest.exchange(
        baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
        HttpMethod.POST,
        bearer(
            pharmacyToken,
            Map.of(
                "master_medicine_id", medicineId, "pharmacy_price", 215.00, "stock_quantity", 48)),
        Map.class);

    rest.exchange(
        baseUrl() + "/api/v1/admin/catalogue/" + bannedId + "/ban",
        HttpMethod.POST,
        bearer(complianceToken, Map.of("reason", "CDSCO ban for search IT")),
        Map.class);

    ResponseEntity<Map> search =
        rest.getForEntity(
            baseUrl() + "/api/v1/catalogue/search?q=augmentin&lat=12.93&lng=77.62", Map.class);
    assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> searchData = data(search);
    assertThat(searchData.get("results")).asList().isNotEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> first =
        (Map<String, Object>) ((List<?>) searchData.get("results")).getFirst();
    assertThat(first.get("best_pharmacy")).isNotNull();
    assertThat(String.valueOf(searchData.get("results"))).doesNotContain(bannedId);

    ResponseEntity<Map> shortQ =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/search?q=a", Map.class);
    assertThat(shortQ.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errorCode(shortQ)).isEqualTo("QUERY_TOO_SHORT");

    ResponseEntity<Map> ac =
        rest.getForEntity(
            baseUrl() + "/api/v1/catalogue/search?q=aug&autocomplete=true", Map.class);
    assertThat(ac.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(ac).get("suggestions")).asList().isNotEmpty();
    assertThat(meta(ac)).containsEntry("cached", false);
    ResponseEntity<Map> ac2 =
        rest.getForEntity(
            baseUrl() + "/api/v1/catalogue/search?q=aug&autocomplete=true", Map.class);
    assertThat(meta(ac2)).containsEntry("cached", true);

    ResponseEntity<Map> detail =
        rest.getForEntity(
            baseUrl() + "/api/v1/catalogue/" + medicineId + "?lat=12.93&lng=77.62", Map.class);
    assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(detail).get("stocking_pharmacies_nearby")).asList().isNotEmpty();

    ResponseEntity<Map> banned =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/" + bannedId, Map.class);
    assertThat(banned.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(errorCode(banned)).isEqualTo("MEDICINE_BANNED");

    ResponseEntity<Map> xSearch =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/search?q=Morphine%20Search", Map.class);
    assertThat(xSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> xResults = (List<Map<String, Object>>) data(xSearch).get("results");
    assertThat(xResults.stream().anyMatch(r -> Boolean.FALSE.equals(r.get("available_online"))))
        .isTrue();

    ResponseEntity<Map> substitutes =
        rest.getForEntity(baseUrl() + "/api/v1/catalogue/substitutes/" + medicineId, Map.class);
    assertThat(substitutes.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(substitutes).get("substitutes")).asList().isEmpty();

    ResponseEntity<Map> avail =
        rest.exchange(
            baseUrl() + "/api/v1/catalogue/check-availability",
            HttpMethod.POST,
            json(
                Map.of(
                    "medicine_ids",
                    List.of(medicineId, scheduleX),
                    "pharmacy_id",
                    PHARMACY_ID.toString())),
            Map.class);
    assertThat(avail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(avail).get("results")).asList().hasSize(2);

    ResponseEntity<Map> pharmacySearch =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue/search?q=augmentin&source=ALL",
            HttpMethod.GET,
            bearer(pharmacyToken, null),
            Map.class);
    assertThat(pharmacySearch.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(pharmacySearch).get("results")).asList().isNotEmpty();
  }

  @Test
  void seededMasterSearchFindsCrocinAndPara() {
    ensureSeededMaster();
    String pharmacyToken = pharmacyLogin();
    ResponseEntity<Map> crocin =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue/search?q=crocin&source=MASTER",
            HttpMethod.GET,
            bearer(pharmacyToken, null),
            Map.class);
    assertThat(crocin.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(crocin).get("results")).asList().isNotEmpty();

    ResponseEntity<Map> para =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue/search?q=para&source=MASTER",
            HttpMethod.GET,
            bearer(pharmacyToken, null),
            Map.class);
    assertThat(para.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(para).get("results")).asList().isNotEmpty();
  }

  private void ensureSeededMaster() {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_master WHERE id ="
                + " 'a0000001-0000-4000-8000-000000000001'::uuid",
            Long.class);
    if (count != null && count > 0) {
      return;
    }
    jdbc.update(
        """
        INSERT INTO medicine_master (
          id, name, salt_composition, manufacturer, category_id, form, pack_size,
          pack_unit, schedule, hsn_code, gst_pct, mrp_paise, is_rx_only, is_banned,
          monthly_demand, mapped_pharmacy_count, substitutes, created_at, updated_at
        ) VALUES
          ('a0000001-0000-4000-8000-000000000001', 'Crocin 500mg Tablet',
           'Paracetamol (500mg)', 'GSK', 'c0000001-0000-4000-8000-000000000004',
           'TABLET', 15, 'TABLET', 'OTC', '30049029', 12, 3000, FALSE, FALSE,
           0, 0, '{}', NOW(), NOW()),
          ('a0000001-0000-4000-8000-000000000002', 'Paracetamol 500mg Tablet',
           'Paracetamol (500mg)', 'Generic Labs',
           'c0000001-0000-4000-8000-000000000004', 'TABLET', 10, 'TABLET', 'OTC',
           '30049029', 12, 1800, FALSE, FALSE, 0, 0, '{}', NOW(), NOW())
        ON CONFLICT (id) DO NOTHING
        """);
  }

  private String createMedicine(String adminToken, String name, String schedule, double mrp) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
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
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
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
  private static Map<String, Object> meta(ResponseEntity<Map> response) {
    return (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("meta");
  }

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    return String.valueOf(error.get("code"));
  }
}
