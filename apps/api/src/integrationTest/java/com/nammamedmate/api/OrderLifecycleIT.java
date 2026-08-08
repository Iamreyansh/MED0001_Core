package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.order.application.OrderLifecycleService;
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

class OrderLifecycleIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0105");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0014-0014-0014-000000000001");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0014-0014-0014-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "lifecycle-ops@test.in";
  private static final String OWNER_EMAIL = "lifecycle-owner@test.in";
  private static final String PASSWORD = "LifeCycle1!";
  private static final String CUSTOMER_PHONE = "+919999900054";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrderLifecycleService lifecycle;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM order_status_event WHERE order_id IN (SELECT id FROM orders WHERE customer_id"
            + " IN (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM customer_addresses WHERE customer_id IN (SELECT id FROM customers WHERE phone ="
            + " ?)",
        CUSTOMER_PHONE);
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", OPS_ID, OPS_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'LifeIT%'");
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PH1);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Life Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    insertPharmacy(PH1, "Life Sai", "PHM-L1", 12.9350, 77.6130);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Life Owner', ?, ?,"
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

    String customerToken = customerLogin(CUSTOMER_PHONE);
    customerId =
        jdbc.queryForObject("SELECT id FROM customers WHERE phone = ?", UUID.class, CUSTOMER_PHONE);
    addressId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO customer_addresses (
          id, customer_id, label, flat_building, area_locality, city, state, pincode,
          latitude, longitude, is_default, created_at, updated_at
        ) VALUES (?, ?, 'Home', '12', 'Koramangala', 'Bengaluru', 'KA', '560034',
          12.9345, 77.6125, true, NOW(), NOW())
        """,
        addressId,
        customerId);
    jdbc.update("UPDATE customers SET default_address_id = ? WHERE id = ?", addressId, customerId);
    assertThat(customerToken).isNotBlank();
  }

  @Test
  void acceptAdvanceTrackRejectTimeoutAndInvalidTransition() {
    String opsToken = adminLogin(OPS_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();
    String med = createMedicine(opsToken, "LifeIT Para", "OTC", 85.00);
    mapMedicine(pharmacyToken, med, 85.00, 200);

    String orderId = placeCod(customerToken, med);
    ResponseEntity<Map> accepted =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + orderId + "/accept",
            HttpMethod.POST,
            bearer(pharmacyToken, null),
            Map.class);
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(accepted).get("status")).isEqualTo("ACCEPTED");

    ResponseEntity<Map> packing =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + orderId + "/status",
            HttpMethod.PATCH,
            bearer(pharmacyToken, Map.of("status", "PACKING", "notes", "Started packing")),
            Map.class);
    assertThat(packing.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> skip =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + orderId + "/status",
            HttpMethod.PATCH,
            bearer(pharmacyToken, Map.of("status", "OUT_FOR_DELIVERY")),
            Map.class);
    assertThat(skip.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(skip)).isEqualTo("INVALID_STATUS_TRANSITION");

    ResponseEntity<Map> ready =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + orderId + "/status",
            HttpMethod.PATCH,
            bearer(pharmacyToken, Map.of("status", "READY_FOR_PICKUP")),
            Map.class);
    assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
    String otpHash =
        jdbc.queryForObject(
            "SELECT delivery_otp_hash FROM orders WHERE id = ?::uuid", String.class, orderId);
    assertThat(otpHash).isNotBlank();
    assertThat(otpHash).doesNotContainPattern("^[0-9]{4}$");

    UUID riderId = UUID.randomUUID();
    ResponseEntity<Map> assigned =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + orderId + "/assign-rider",
            HttpMethod.POST,
            bearer(pharmacyToken, Map.of("rider_id", riderId.toString())),
            Map.class);
    assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> forced =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/status",
            HttpMethod.PATCH,
            bearer(
                opsToken,
                Map.of("status", "OUT_FOR_DELIVERY", "reason", "ops advance", "notes", "ok")),
            Map.class);
    assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> tracking =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + orderId + "/tracking",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(tracking.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps = (List<Map<String, Object>>) data(tracking).get("steps");
    assertThat(steps.get(4).get("completed")).isEqualTo(true);
    assertThat(steps.get(5).get("completed")).isEqualTo(false);
    assertThat(data(tracking).get("eta_minutes")).isNotNull();

    ResponseEntity<Map> delivered =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/status",
            HttpMethod.PATCH,
            bearer(opsToken, Map.of("status", "DELIVERED", "reason", "complete")),
            Map.class);
    assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> timeline =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + orderId + "/timeline",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(timeline.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<?> events = (List<?>) data(timeline).get("events");
    assertThat(events).hasSizeGreaterThanOrEqualTo(6);

    // reject path on a fresh order
    String rejectId = placeCod(customerToken, med);
    ResponseEntity<Map> rejected =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/orders/" + rejectId + "/reject",
            HttpMethod.POST,
            bearer(
                pharmacyToken,
                Map.of("reason", "OUT_OF_STOCK", "message", "Unavailable right now")),
            Map.class);
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(rejected).get("status")).isEqualTo("CANCELLED");

    // timeout cancel job
    String timeoutId = placeCod(customerToken, med);
    jdbc.update(
        "UPDATE orders SET confirmed_at = NOW() - INTERVAL '11 minutes' WHERE id = ?::uuid",
        timeoutId);
    assertThat(lifecycle.cancelTimedOutAcceptances()).isGreaterThanOrEqualTo(1);
    String cancelReason =
        jdbc.queryForObject(
            "SELECT cancel_reason FROM orders WHERE id = ?::uuid", String.class, timeoutId);
    assertThat(cancelReason).isEqualTo("PHARMACY_ACCEPTANCE_TIMEOUT");
  }

  private String placeCod(String customerToken, String med) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", med, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(add.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cartId = String.valueOf(data(add).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> placed =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", cartId, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return String.valueOf(data(placed).get("order_id"));
  }

  private void insertPharmacy(UUID id, String name, String code, double lat, double lng) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline, phone,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', '+91-8022334499', NOW(), NOW())",
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
                    "it-life")),
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

  private static HttpEntity<?> bearerIdem(String token, Map<String, ?> body, String idem) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idem);
    return new HttpEntity<>(body, headers);
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
