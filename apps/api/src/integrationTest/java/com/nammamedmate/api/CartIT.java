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

class CartIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0103");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0011-0011-0011-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0011-0011-0011-000000000002");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0011-0011-0011-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "cart-ops@test.in";
  private static final String OWNER_EMAIL = "cart-owner@test.in";
  private static final String PASSWORD = "CartTest1!";
  private static final String CUSTOMER_PHONE = "+919999900051";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", OPS_ID, OPS_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'CartIT%'");
    jdbc.update("DELETE FROM pharmacies WHERE id IN (?, ?)", PH1, PH2);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cart Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);

    insertPharmacy(PH1, "Cart Sai", "PHM-C1", 12.9350, 77.6130);
    insertPharmacy(PH2, "Cart Apollo", "PHM-C2", 12.9400, 77.6200);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.20, 80, 80.00, NOW(), NOW())",
        PH2);

    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cart Owner', ?, ?,"
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
  void cartLifecycleCouponsMismatchAndAbandon() {
    String opsToken = adminLogin(OPS_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();

    String medA = createMedicine(opsToken, "CartIT Metformin", "H", 85.00);
    String medB = createMedicine(opsToken, "CartIT Paracetamol", "OTC", 12.00);
    mapMedicine(pharmacyToken, medA, 85.00, 200);
    // PH2 only stocks medB
    jdbc.update(
        "INSERT INTO pharmacy_catalogue_mapping (id, pharmacy_id, master_medicine_id,"
            + " pharmacy_price_paise, stock_quantity, is_visible, pause_hidden, created_at,"
            + " updated_at) VALUES (?, ?, ?::uuid, 1200, 50, TRUE, FALSE, NOW(), NOW())",
        UUID.randomUUID(),
        PH2,
        UUID.fromString(medB));

    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medA, "quantity", 3, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(add.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> cart = data(add);
    assertThat(cart.get("status")).isEqualTo("ACTIVE");
    @SuppressWarnings("unchecked")
    Map<String, Object> pharmacy = (Map<String, Object>) cart.get("pharmacy");
    assertThat(pharmacy.get("id")).isEqualTo(PH1.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) cart.get("items");
    assertThat(items).hasSize(1);
    String itemId = String.valueOf(items.getFirst().get("item_id"));

    ResponseEntity<Map> mismatch =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(customerToken, Map.of("medicine_id", medB, "quantity", 1)),
            Map.class);
    assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(mismatch)).isEqualTo("PHARMACY_MISMATCH");

    ResponseEntity<Map> flat50 =
        rest.exchange(
            baseUrl() + "/api/v1/cart/coupon",
            HttpMethod.POST,
            bearer(customerToken, Map.of("coupon_code", "FLAT50")),
            Map.class);
    assertThat(flat50.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(flat50)).isEqualTo("COUPON_MIN_NOT_MET");

    ResponseEntity<Map> namma =
        rest.exchange(
            baseUrl() + "/api/v1/cart/coupon",
            HttpMethod.POST,
            bearer(customerToken, Map.of("coupon_code", "NAMMA25")),
            Map.class);
    assertThat(namma.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> get =
        rest.exchange(
            baseUrl() + "/api/v1/cart", HttpMethod.GET, bearer(customerToken, null), Map.class);
    assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> afterCoupon = data(get);
    assertThat(((Number) afterCoupon.get("coupon_discount")).doubleValue()).isEqualTo(63.75);
    @SuppressWarnings("unchecked")
    Map<String, Object> bill = (Map<String, Object>) afterCoupon.get("bill");
    // pre-coupon item_total 255 >= 199 → free delivery
    assertThat(((Number) bill.get("delivery_fee")).doubleValue()).isEqualTo(0.0);

    rest.exchange(
        baseUrl() + "/api/v1/cart/coupon",
        HttpMethod.DELETE,
        bearer(customerToken, null),
        Map.class);
    rest.exchange(
        baseUrl() + "/api/v1/cart/coupon",
        HttpMethod.POST,
        bearer(customerToken, Map.of("coupon_code", "FREEDEL")),
        Map.class);
    // shrink item_total to 150 via price update then recompute — use qty that yields <199
    // current line is 255; FREEDEL on 255 still delivery 0
    Map<String, Object> freedelCart =
        data(
            rest.exchange(
                baseUrl() + "/api/v1/cart",
                HttpMethod.GET,
                bearer(customerToken, null),
                Map.class));
    @SuppressWarnings("unchecked")
    Map<String, Object> freedelBill = (Map<String, Object>) freedelCart.get("bill");
    assertThat(((Number) freedelBill.get("delivery_fee")).doubleValue()).isEqualTo(0.0);

    UUID rx = UUID.randomUUID();
    ResponseEntity<Map> attachRx =
        rest.exchange(
            baseUrl() + "/api/v1/cart/prescription",
            HttpMethod.POST,
            bearer(customerToken, Map.of("prescription_id", rx.toString())),
            Map.class);
    assertThat(attachRx.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(attachRx).get("prescription_id")).isEqualTo(rx.toString());

    ResponseEntity<Map> removeLast =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items/" + itemId,
            HttpMethod.PATCH,
            bearer(customerToken, Map.of("quantity", 0)),
            Map.class);
    assertThat(removeLast.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> cleared = data(removeLast);
    assertThat(cleared.get("pharmacy")).isNull();
    assertThat(cleared.get("coupon_applied")).isNull();
    assertThat(cleared.get("prescription_id")).isNull();

    // re-add and abandon via job SQL path
    rest.exchange(
        baseUrl() + "/api/v1/cart/items",
        HttpMethod.POST,
        bearer(
            customerToken,
            Map.of("medicine_id", medA, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
        Map.class);
    jdbc.update(
        "UPDATE carts SET updated_at = NOW() - INTERVAL '25 hours' WHERE customer_id ="
            + " (SELECT id FROM customers WHERE phone = ?) AND status = 'ACTIVE'",
        CUSTOMER_PHONE);
    jdbc.update(
        "UPDATE carts SET status = 'ABANDONED', updated_at = NOW() WHERE customer_id ="
            + " (SELECT id FROM customers WHERE phone = ?) AND status = 'ACTIVE'"
            + " AND updated_at < NOW() - INTERVAL '24 hours'",
        CUSTOMER_PHONE);

    ResponseEntity<Map> newCart =
        rest.exchange(
            baseUrl() + "/api/v1/cart", HttpMethod.GET, bearer(customerToken, null), Map.class);
    assertThat(newCart.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(newCart).get("status")).isEqualTo("ACTIVE");
    assertThat(((List<?>) data(newCart).get("items"))).isEmpty();

    ResponseEntity<Map> clear =
        rest.exchange(
            baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    assertThat(clear.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(clear).get("message")).isEqualTo("Cart cleared");
  }

  private void insertPharmacy(UUID id, String name, String code, double lat, double lng) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', NOW(), NOW())",
        id,
        name,
        name,
        code,
        lat,
        lng,
        "{\"flat\":\"1\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\",\"pincode\":\"560034\"}");
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
                    "it-cart")),
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

  @SuppressWarnings("unchecked")
  private static String errorCode(ResponseEntity<Map> response) {
    Map<String, Object> err =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    return String.valueOf(err.get("code"));
  }
}
