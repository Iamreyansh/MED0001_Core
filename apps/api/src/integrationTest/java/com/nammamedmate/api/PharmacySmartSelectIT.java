package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
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

class PharmacySmartSelectIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0102");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0010-0010-0010-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0010-0010-0010-000000000002");
  private static final UUID PH_CLOSED = UUID.fromString("aaaaaaaa-0010-0010-0010-000000000003");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0010-0010-0010-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "smart-select-ops@test.in";
  private static final String OWNER_EMAIL = "smart-select-owner@test.in";
  private static final String PASSWORD = "SmartSelect1!";
  private static final String CUSTOMER_PHONE = "+919999900050";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id IN (?, ?, ?)",
        PH1,
        PH2,
        PH_CLOSED);
    jdbc.update(
        "DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id IN (?, ?, ?)",
        PH1,
        PH2,
        PH_CLOSED);
    jdbc.update(
        "DELETE FROM pharmacy_performance_snapshot WHERE pharmacy_id IN (?, ?, ?)",
        PH1,
        PH2,
        PH_CLOSED);
    jdbc.update(
        "DELETE FROM pharmacy_operating_hours WHERE pharmacy_id IN (?, ?, ?)", PH1, PH2, PH_CLOSED);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'SmartSelect%'");
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", OPS_ID, OPS_EMAIL);
    jdbc.update("DELETE FROM pharmacies WHERE id IN (?, ?, ?)", PH1, PH2, PH_CLOSED);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Smart Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);

    insertPharmacy(
        PH1,
        "Sai Medicals",
        "PHM-SS1",
        12.9350,
        77.6130,
        true,
        false,
        "{\"flat\":\"12\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}");
    insertPharmacy(
        PH2,
        "Apollo Nearby",
        "PHM-SS2",
        12.9400,
        77.6200,
        true,
        false,
        "{\"flat\":\"1\",\"area\":\"BTM\",\"city\":\"Bengaluru\",\"pincode\":\"560076\"}");
    insertPharmacy(
        PH_CLOSED,
        "Offline Shop",
        "PHM-SS3",
        12.9348,
        77.6128,
        false,
        false,
        "{\"flat\":\"9\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}");

    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 312, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.20, 100, 80.00, NOW(), NOW())",
        PH2);

    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'SS Owner', ?, ?,"
            + " 'ACTIVE', 0, NOW(), NOW())",
        STAFF1,
        OWNER_EMAIL,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF1,
        PH1,
        OWNER_ROLE_ID);
  }

  @Test
  void smartSelectNearbyStorefrontProductsAvailability() {
    String opsToken = adminLogin(OPS_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();

    String inStock = createMedicine(opsToken, "SmartSelect Metformin", "H", 28.50);
    String oos = createMedicine(opsToken, "SmartSelect Glipizide", "OTC", 15.00);
    String banned = createMedicine(opsToken, "SmartSelect Banned", "OTC", 10.00);

    mapMedicine(pharmacyToken, inStock, 25.65, 200);
    mapMedicine(pharmacyToken, oos, 12.00, 0);
    mapMedicine(pharmacyToken, banned, 9.00, 50);
    jdbc.update("UPDATE medicine_master SET is_banned = TRUE WHERE id = ?::uuid", banned);

    // PH2 also stocks the medicine farther away
    jdbc.update(
        "INSERT INTO pharmacy_catalogue_mapping (id, pharmacy_id, master_medicine_id,"
            + " pharmacy_price_paise, stock_quantity, is_visible, pause_hidden, created_at,"
            + " updated_at) VALUES (?, ?, ?::uuid, 2500, 50, TRUE, FALSE, NOW(), NOW())",
        UUID.randomUUID(),
        PH2,
        UUID.fromString(inStock));

    ResponseEntity<Map> smart =
        rest.exchange(
            baseUrl() + "/api/v1/cart/smart-select",
            HttpMethod.POST,
            bearer(customerToken, Map.of("medicine_id", inStock, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(smart.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> smartData = data(smart);
    assertThat(smartData.get("available")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> selected = (Map<String, Object>) smartData.get("selected_pharmacy");
    assertThat(selected.get("id")).isEqualTo(PH1.toString());

    ResponseEntity<Map> unavailable =
        rest.exchange(
            baseUrl() + "/api/v1/cart/smart-select",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of(
                    "medicine_id", UUID.randomUUID().toString(), "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(unavailable.getStatusCode())
        .as("unavailable smart-select body=%s", unavailable.getBody())
        .isEqualTo(HttpStatus.OK);
    assertThat(data(unavailable).get("available")).isEqualTo(false);

    ResponseEntity<Map> nearby =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacies/nearby?lat=12.9345&lng=77.6125&radius_km=15&limit=10",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(nearby.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> nearbyBody = Objects.requireNonNull(nearby.getBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> nearbyMeta = (Map<String, Object>) nearbyBody.get("meta");
    assertThat(((Number) nearbyMeta.get("radius_km")).doubleValue()).isEqualTo(10.0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nearbyData = (List<Map<String, Object>>) nearbyBody.get("data");
    assertThat(nearbyData.stream().map(m -> m.get("id")).toList())
        .contains(PH1.toString(), PH2.toString())
        .doesNotContain(PH_CLOSED.toString());

    ResponseEntity<Map> storefront =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacies/" + PH1 + "?lat=12.9345&lng=77.6125",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(storefront.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(storefront).get("name")).isEqualTo("Sai Medicals");

    ResponseEntity<Map> products =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacies/" + PH1 + "/products?page=1&limit=20",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> productRows =
        (List<Map<String, Object>>) Objects.requireNonNull(products.getBody()).get("data");
    List<String> productIds =
        productRows.stream().map(p -> String.valueOf(p.get("product_id"))).toList();
    assertThat(productIds).contains(inStock).doesNotContain(banned, oos);

    ResponseEntity<Map> availability =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacies/availability-check",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of(
                    "pharmacy_id", PH1.toString(), "medicine_ids", List.of(inStock, oos, banned))),
            Map.class);
    assertThat(availability.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> avail = data(availability);
    assertThat((List<?>) avail.get("available")).hasSize(1);
    assertThat((List<?>) avail.get("unavailable")).hasSize(2);
  }

  private void insertPharmacy(
      UUID id,
      String name,
      String code,
      double lat,
      double lng,
      boolean online,
      boolean forcedOffline,
      String addressJson) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', ?, ?,"
            + " ?, ?, ?::jsonb, 'Free delivery on orders above ₹199', NOW(), NOW())",
        id,
        name,
        name,
        code,
        online,
        forcedOffline,
        lat,
        lng,
        addressJson);
  }

  private void mapMedicine(String pharmacyToken, String medicineId, double price, int qty) {
    ResponseEntity<Map> mapped =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/catalogue-mapping",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of(
                    "master_medicine_id",
                    medicineId,
                    "pharmacy_price",
                    price,
                    "stock_quantity",
                    qty)),
            Map.class);
    assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private String createMedicine(String adminToken, String name, String schedule, double mrp) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("salt_composition", "Salt " + UUID.randomUUID());
    body.put("manufacturer", "USV Ltd");
    body.put("category_id", CATEGORY.toString());
    body.put("form", "TABLET");
    body.put("pack_size", 10);
    body.put("pack_unit", "TABLET");
    body.put("schedule", schedule);
    body.put("hsn_code", "30041090");
    body.put("gst_pct", 12);
    body.put("mrp", mrp);
    body.put("is_rx_only", "H".equals(schedule));
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

  private String customerLogin(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp", json(Map.of("phone", phone)), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData =
        (Map<String, Object>) Objects.requireNonNull(send.getBody()).get("data");
    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            json(
                Map.of(
                    "session_id",
                    sendData.get("session_id"),
                    "phone",
                    phone,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-smart-select")),
            Map.class);
    return String.valueOf(data(verify).get("access_token"));
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
}
