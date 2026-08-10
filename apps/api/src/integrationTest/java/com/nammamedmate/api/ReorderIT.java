package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.api.support.PrescriptionFixtures;
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

class ReorderIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0108");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0017-0017-0017-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0017-0017-0017-000000000002");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0017-0017-0017-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "reorder-ops@test.in";
  private static final String OWNER_EMAIL = "reorder-owner@test.in";
  private static final String PASSWORD = "ReorderT1!";
  private static final String CUSTOMER_PHONE = "+919999900057";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM reorder_attempt_log WHERE customer_id IN (SELECT id FROM customers WHERE phone"
            + " = ?)",
        CUSTOMER_PHONE);
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
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id IN (?, ?)", PH1, PH2);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", OPS_ID, OPS_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'ReorderIT%'");
    jdbc.update("DELETE FROM pharmacies WHERE id IN (?, ?)", PH1, PH2);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Reorder Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);
    // Far from Bangalore IT cluster so nearby/rx-quote ITs are not polluted.
    insertPharmacy(PH1, "Reorder Sai", "PHM-R1", 13.0827, 80.2707);
    insertPharmacy(PH2, "Reorder Apollo", "PHM-R2", 13.0835, 80.2715);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.40, 80, 88.00, NOW(), NOW())",
        PH2);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Reorder Owner', ?, ?,"
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
        ) VALUES (?, ?, 'Home', '12', 'T Nagar', 'Chennai', 'TN', '600017',
          13.0827, 80.2707, true, NOW(), NOW())
        """,
        addressId,
        customerId);
    jdbc.update("UPDATE customers SET default_address_id = ? WHERE id = ?", addressId, customerId);
    assertThat(customerToken).isNotBlank();
  }

  @Test
  void reorderHistoryActiveAndPharmacyChange() {
    String opsToken = adminLogin(OPS_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();

    String medA = createMedicine(opsToken, "ReorderIT A", "OTC", 85.00);
    String medB = createMedicine(opsToken, "ReorderIT B", "OTC", 40.00);
    String medRx = createMedicine(opsToken, "ReorderIT Rx", "H", 90.00);
    mapMedicine(pharmacyToken, medA, 85.00, 200);
    mapMedicine(pharmacyToken, medB, 40.00, 200);
    mapMedicine(pharmacyToken, medRx, 90.00, 200);
    // PH2 stocks A+B only (for fallback / partial)
    jdbc.update(
        """
        INSERT INTO pharmacy_catalogue_mapping (
          id, pharmacy_id, master_medicine_id, pharmacy_price_paise, stock_quantity,
          is_visible, created_at, updated_at
        ) VALUES (?, ?, ?::uuid, 9000, 200, true, NOW(), NOW()),
                 (?, ?, ?::uuid, 4500, 200, true, NOW(), NOW())
        """,
        UUID.randomUUID(),
        PH2,
        medA,
        UUID.randomUUID(),
        PH2,
        medB);

    String deliveredId = placeCodAndMarkDelivered(customerToken, medA);
    String activeId = placeCod(customerToken, medB);
    String cancelledId = placeCod(customerToken, medA);
    jdbc.update(
        "UPDATE orders SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?::uuid",
        UUID.fromString(cancelledId));

    // AC7 history = DELIVERED|CANCELLED only
    ResponseEntity<Map> history =
        rest.exchange(
            baseUrl() + "/api/v1/orders/history?status=ALL",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hist = (List<Map<String, Object>>) dataList(history);
    assertThat(hist).isNotEmpty();
    assertThat(hist.stream().map(r -> r.get("status")).toList())
        .containsOnly("DELIVERED", "CANCELLED");

    // AC8 active includes in-progress
    ResponseEntity<Map> active =
        rest.exchange(
            baseUrl() + "/api/v1/orders/active",
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> act = (List<Map<String, Object>>) dataList(active);
    assertThat(act.stream().map(r -> String.valueOf(r.get("order_id"))).toList())
        .contains(activeId);

    // AC1 same pharmacy reorder
    ResponseEntity<Map> same =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + deliveredId + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", false)),
            Map.class);
    assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> sameData = data(same);
    assertThat(sameData.get("prescription_attached")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    Map<String, Object> samePh = (Map<String, Object>) sameData.get("pharmacy");
    assertThat(String.valueOf(samePh.get("id"))).isEqualTo(PH1.toString());
    String priorCart = String.valueOf(sameData.get("cart_id"));

    // AC4 Rx path — reorder Rx order without attaching Rx
    String rxOrder = placeCodWithRxAndMarkDelivered(customerToken, medRx);
    ResponseEntity<Map> activeBefore =
        rest.exchange(
            baseUrl() + "/api/v1/cart", HttpMethod.GET, bearer(customerToken, null), Map.class);
    String activeBeforeId = String.valueOf(data(activeBefore).get("cart_id"));
    ResponseEntity<Map> rxReorder =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + rxOrder + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", false)),
            Map.class);
    assertThat(rxReorder.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> rxData = data(rxReorder);
    assertThat(rxData.get("prescription_required")).isEqualTo(true);
    assertThat(rxData.get("prescription_attached")).isEqualTo(false);
    String rxCart = String.valueOf(rxData.get("cart_id"));
    assertThat(rxCart).isNotEqualTo(activeBeforeId);
    String abandonedStatus =
        jdbc.queryForObject(
            "SELECT status FROM carts WHERE id = ?::uuid", String.class, activeBeforeId);
    assertThat(abandonedStatus).isEqualTo("ABANDONED");
    // first reorder cart was checked out by subsequent placement; still not ACTIVE
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM carts WHERE id = ?::uuid", String.class, priorCart))
        .isIn("CHECKED_OUT", "ABANDONED");

    // AC2/AC3 pharmacy closed → 409 then confirm
    jdbc.update("UPDATE pharmacies SET is_online = false WHERE id = ?", PH1);
    ResponseEntity<Map> needConfirm =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + deliveredId + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", false)),
            Map.class);
    assertThat(needConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(errorCode(needConfirm)).isEqualTo("PHARMACY_CHANGE_REQUIRED");
    @SuppressWarnings("unchecked")
    Map<String, Object> err = (Map<String, Object>) needConfirm.getBody().get("error");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) err.get("details");
    assertThat(details).containsKey("suggested_pharmacy");

    ResponseEntity<Map> confirmed =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + deliveredId + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", true)),
            Map.class);
    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> confPh = (Map<String, Object>) data(confirmed).get("pharmacy");
    assertThat(String.valueOf(confPh.get("id"))).isEqualTo(PH2.toString());
    assertThat(confPh.get("note")).isNotNull();
    String confCart = String.valueOf(data(confirmed).get("cart_id"));
    String rxAbandoned =
        jdbc.queryForObject("SELECT status FROM carts WHERE id = ?::uuid", String.class, rxCart);
    assertThat(rxAbandoned).isEqualTo("ABANDONED");
    assertThat(confCart).isNotEqualTo(rxCart);

    // AC6 partial: order with A+B, drop B at PH2
    jdbc.update("UPDATE pharmacies SET is_online = true WHERE id = ?", PH1);
    String multi = placeCodTwoItemsAndDeliver(customerToken, medA, medB);
    jdbc.update("UPDATE pharmacies SET is_online = false WHERE id = ?", PH1);
    jdbc.update(
        "UPDATE pharmacy_catalogue_mapping SET stock_quantity = 0 WHERE pharmacy_id = ? AND"
            + " master_medicine_id = ?::uuid",
        PH2,
        UUID.fromString(medB));
    ResponseEntity<Map> partial =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + multi + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", true)),
            Map.class);
    assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> added = (List<Map<String, Object>>) data(partial).get("items_added");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> excluded =
        (List<Map<String, Object>>) data(partial).get("excluded_items");
    assertThat(added).hasSize(1);
    assertThat(excluded).hasSize(1);
    assertThat(excluded.getFirst().get("reason")).isEqualTo("OUT_OF_STOCK");

    // AC5 none available
    jdbc.update(
        "UPDATE pharmacy_catalogue_mapping SET stock_quantity = 0 WHERE pharmacy_id = ? AND"
            + " master_medicine_id = ?::uuid",
        PH2,
        UUID.fromString(medA));
    ResponseEntity<Map> none =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + deliveredId + "/reorder",
            HttpMethod.POST,
            bearer(customerToken, Map.of("confirm_pharmacy_change", true)),
            Map.class);
    assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(none)).isEqualTo("NO_ITEMS_AVAILABLE");
  }

  private String placeCodAndMarkDelivered(String customerToken, String medicineId) {
    String orderId = placeCod(customerToken, medicineId);
    jdbc.update(
        "UPDATE orders SET status = 'DELIVERED', delivered_at = NOW(), updated_at = NOW() WHERE"
            + " id = ?::uuid",
        UUID.fromString(orderId));
    return orderId;
  }

  private String placeCodTwoItemsAndDeliver(String customerToken, String medA, String medB) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    rest.exchange(
        baseUrl() + "/api/v1/cart/items",
        HttpMethod.POST,
        bearer(
            customerToken,
            Map.of("medicine_id", medA, "quantity", 1, "lat", 13.0827, "lng", 80.2707)),
        Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(customerToken, Map.of("medicine_id", medB, "quantity", 1)),
            Map.class);
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
    String orderId = String.valueOf(data(placed).get("order_id"));
    jdbc.update(
        "UPDATE orders SET status = 'DELIVERED', delivered_at = NOW(), updated_at = NOW() WHERE"
            + " id = ?::uuid",
        UUID.fromString(orderId));
    return orderId;
  }

  private String placeCodWithRxAndMarkDelivered(String customerToken, String medicineId) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medicineId, "quantity", 1, "lat", 13.0827, "lng", 80.2707)),
            Map.class);
    assertThat(add.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cartId = String.valueOf(data(add).get("cart_id"));
    UUID rxId = PrescriptionFixtures.insertVerified(jdbc, customerId);
    rest.exchange(
        baseUrl() + "/api/v1/cart/prescription",
        HttpMethod.POST,
        bearer(customerToken, Map.of("prescription_id", rxId.toString())),
        Map.class);
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
    String orderId = String.valueOf(data(placed).get("order_id"));
    jdbc.update(
        "UPDATE orders SET status = 'DELIVERED', delivered_at = NOW(), updated_at = NOW() WHERE"
            + " id = ?::uuid",
        UUID.fromString(orderId));
    return orderId;
  }

  private String placeCod(String customerToken, String medicineId) {
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medicineId, "quantity", 1, "lat", 13.0827, "lng", 80.2707)),
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
    String phone =
        "+91-80" + String.format("%08d", Math.floorMod(id.getLeastSignificantBits(), 100_000_000));
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline, phone,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Chennai', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', ?, NOW(), NOW())",
        id,
        name,
        name,
        code,
        lat,
        lng,
        "{\"flat\":\"1\",\"area\":\"T Nagar\",\"city\":\"Chennai\",\"pincode\":\"600017\"}",
        phone);
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
    body.put("manufacturer", "Reorder Labs");
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

  private String pharmacyLogin() {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/login",
            json(Map.of("identifier", OWNER_EMAIL, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(login).get("access_token"));
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            json(Map.of("email", email, "password", PASSWORD)),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = data(login);
    if (body.containsKey("mfa_required") && Boolean.TRUE.equals(body.get("mfa_required"))) {
      throw new IllegalStateException("unexpected MFA");
    }
    return String.valueOf(body.get("access_token"));
  }

  private String customerLogin(String phone) {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp", json(Map.of("phone", phone)), Map.class);
    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
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
                    "it-reorder")),
            Map.class);
    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    return String.valueOf(data(verify).get("access_token"));
  }

  private HttpEntity<Map<String, Object>> bearer(String token, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  private HttpEntity<Map<String, Object>> bearerIdem(
      String token, Map<String, Object> body, String idem) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idem);
    return new HttpEntity<>(body, headers);
  }

  private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> res) {
    Map<String, Object> body = Objects.requireNonNull(res.getBody());
    return (Map<String, Object>) body.get("data");
  }

  @SuppressWarnings("unchecked")
  private Object dataList(ResponseEntity<Map> res) {
    Map<String, Object> body = Objects.requireNonNull(res.getBody());
    return body.get("data");
  }

  private String errorCode(ResponseEntity<Map> res) {
    @SuppressWarnings("unchecked")
    Map<String, Object> body = Objects.requireNonNull(res.getBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    return String.valueOf(error.get("code"));
  }
}
