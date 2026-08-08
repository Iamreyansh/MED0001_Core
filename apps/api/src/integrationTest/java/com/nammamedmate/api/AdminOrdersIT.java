package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.order.application.AdminOrderService;
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

class AdminOrdersIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0108");
  private static final UUID FIN_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0109");
  private static final UUID SUP_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0110");
  private static final UUID CMP_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0018-0018-0018-000000000001");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0018-0018-0018-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "admin-ord-ops@test.in";
  private static final String FIN_EMAIL = "admin-ord-fin@test.in";
  private static final String SUP_EMAIL = "admin-ord-sup@test.in";
  private static final String CMP_EMAIL = "admin-ord-cmp@test.in";
  private static final String OWNER_EMAIL = "admin-ord-owner@test.in";
  private static final String PASSWORD = "AdminOrd1!";
  private static final String CUSTOMER_PHONE = "+919999900058";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AdminOrderService adminOrders;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    jdbc.update(
        "DELETE FROM order_note WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN"
            + " (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM order_dispute WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN"
            + " (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM admin_order_export_job WHERE requested_by IN (?, ?, ?, ?)",
        OPS_ID,
        FIN_ID,
        SUP_ID,
        CMP_ID);
    jdbc.update(
        "DELETE FROM order_status_event WHERE order_id IN (SELECT id FROM orders WHERE customer_id"
            + " IN (SELECT id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM refund WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN (SELECT"
            + " id FROM customers WHERE phone = ?))",
        CUSTOMER_PHONE);
    jdbc.update(
        "DELETE FROM order_cancellation WHERE order_id IN (SELECT id FROM orders WHERE customer_id"
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
    jdbc.update(
        "DELETE FROM sessions WHERE user_id IN (?, ?, ?, ?)", OPS_ID, FIN_ID, SUP_ID, CMP_ID);
    jdbc.update(
        "DELETE FROM admin_auth_events WHERE admin_id IN (?, ?, ?, ?)",
        OPS_ID,
        FIN_ID,
        SUP_ID,
        CMP_ID);
    jdbc.update(
        "DELETE FROM admin_staff WHERE id IN (?, ?, ?, ?) OR email IN (?, ?, ?, ?)",
        OPS_ID,
        FIN_ID,
        SUP_ID,
        CMP_ID,
        OPS_EMAIL,
        FIN_EMAIL,
        SUP_EMAIL,
        CMP_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'AdminOrdIT%'");
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PH1);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    insertAdmin(OPS_ID, "Ops Admin", OPS_EMAIL, "admin_operations", hash);
    insertAdmin(FIN_ID, "Fin Admin", FIN_EMAIL, "admin_finance", hash);
    insertAdmin(SUP_ID, "Sup Admin", SUP_EMAIL, "admin_support", hash);
    insertAdmin(CMP_ID, "Cmp Admin", CMP_EMAIL, "admin_compliance", hash);

    insertPharmacy(PH1, "AdminOrd Sai", "PHM-AO8", 12.9350, 77.6130);
    jdbc.update("UPDATE pharmacies SET commission_pct = 10.00 WHERE id = ?", PH1);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'AdminOrd Owner', ?, ?,"
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
    jdbc.update("UPDATE customers SET name = 'Ravi AdminOrd' WHERE id = ?", customerId);
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
  void adminOrderOversightAcceptanceCriteria() {
    String opsToken = adminLogin(OPS_EMAIL);
    String finToken = adminLogin(FIN_EMAIL);
    String supToken = adminLogin(SUP_EMAIL);
    String cmpToken = adminLogin(CMP_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();
    String med = createMedicine(opsToken, "AdminOrdIT Para", "OTC", 85.00);
    mapMedicine(pharmacyToken, med, 85.00, 500);

    String orderId = placeCod(customerToken, med);
    jdbc.update(
        "UPDATE orders SET status = 'OUT_FOR_DELIVERY', payment_status = 'PENDING_COLLECTION',"
            + " confirmed_at = NOW() - INTERVAL '25 minutes',"
            + " sla_deadline = NOW() + INTERVAL '2 minutes',"
            + " prescription_id = ? WHERE id = ?::uuid",
        UUID.randomUUID(),
        orderId);
    String safeOrder = placeCod(customerToken, med);
    jdbc.update(
        "UPDATE orders SET status = 'OUT_FOR_DELIVERY', confirmed_at = NOW(),"
            + " sla_deadline = NOW() + INTERVAL '20 minutes' WHERE id = ?::uuid",
        safeOrder);

    // AC1 SLA_RISK
    ResponseEntity<Map> slaRisk =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders?segment=SLA_RISK",
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(slaRisk.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> riskOrders = (List<Map<String, Object>>) data(slaRisk).get("orders");
    assertThat(riskOrders).extracting(m -> m.get("order_id")).contains(orderId);
    assertThat(riskOrders).extracting(m -> m.get("order_id")).doesNotContain(safeOrder);

    // AC2 compliance Rx redaction
    ResponseEntity<Map> detailCmp =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId,
            HttpMethod.GET,
            bearer(cmpToken, null),
            Map.class);
    assertThat(detailCmp.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> rxCard = (Map<String, Object>) data(detailCmp).get("prescription_card");
    assertThat(rxCard).containsKeys("id", "type");
    assertThat(rxCard).doesNotContainKey("file_url");

    // AC3 dispute banner
    ResponseEntity<Map> dispute =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/dispute",
            HttpMethod.POST,
            bearer(supToken, Map.of("reason", "Not delivered", "liable_party", "RIDER")),
            Map.class);
    assertThat(dispute.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map> detailOps =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId,
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(data(detailOps).get("is_disputed")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> banner = (Map<String, Object>) data(detailOps).get("dispute_banner");
    assertThat(banner.get("liable_party")).isEqualTo("RIDER");

    // AC4 finance note
    ResponseEntity<Map> note =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/note",
            HttpMethod.POST,
            bearer(finToken, Map.of("note", "Called customer", "is_pinned", true)),
            Map.class);
    assertThat(note.getStatusCode()).isEqualTo(HttpStatus.OK);
    String noteId = String.valueOf(data(note).get("note_id"));
    ResponseEntity<Map> detailAfterNote =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId,
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notes =
        (List<Map<String, Object>>) data(detailAfterNote).get("internal_notes");
    assertThat(notes).extracting(m -> m.get("note")).contains("Called customer");

    // Customer order GET must not expose internal_notes
    ResponseEntity<Map> customerOrder =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + orderId,
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(customerOrder.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(customerOrder)).doesNotContainKey("internal_notes");

    // AC5 delete note 405
    ResponseEntity<Map> deleteNote =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/notes/" + noteId,
            HttpMethod.DELETE,
            bearer(opsToken, null),
            Map.class);
    assertThat(deleteNote.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

    // AC6 live-feed
    ResponseEntity<Map> feed =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/live-feed",
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> liveOrders = (List<Map<String, Object>>) data(feed).get("orders");
    assertThat(liveOrders.getFirst().get("order_id")).isEqualTo(orderId);
    assertThat(liveOrders.getFirst().get("sla_risk")).isEqualTo(true);

    // AC8 reassign rider + ADMIN event
    UUID newRider = UUID.fromString("dddddddd-0018-4000-8000-000000000099");
    ResponseEntity<Map> reassign =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders/" + orderId + "/rider",
            HttpMethod.PATCH,
            bearer(
                opsToken, Map.of("rider_id", newRider.toString(), "reason", "Rider unavailable")),
            Map.class);
    assertThat(reassign.getStatusCode()).isEqualTo(HttpStatus.OK);
    Integer adminEvents =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_status_event WHERE order_id = ?::uuid AND actor_type ="
                + " 'ADMIN' AND notes LIKE 'Rider reassigned%'",
            Integer.class, orderId);
    assertThat(adminEvents).isGreaterThanOrEqualTo(1);

    // AC7 async export >10000
    UUID cartId =
        jdbc.queryForObject("SELECT cart_id FROM orders WHERE id = ?::uuid", UUID.class, orderId);
    jdbc.update(
        """
        INSERT INTO orders (
          id, order_number, customer_id, pharmacy_id, cart_id, items,
          item_total_paise, coupon_discount_paise, delivery_fee_paise, handling_fee_paise,
          wallet_applied_paise, total_payable_paise, payment_method, payment_status,
          delivery_address_id, status, created_at, updated_at
        )
        SELECT gen_random_uuid(),
               'ORD-AO-' || LPAD(gs::text, 6, '0'),
               ?, ?, ?, '[]'::jsonb,
               1000, 0, 0, 0, 0, 1000, 'COD', 'PENDING_COLLECTION',
               ?, 'DELIVERED', NOW(), NOW()
        FROM generate_series(1, 10001) gs
        """,
        customerId,
        PH1,
        cartId,
        addressId);
    ResponseEntity<Map> export =
        rest.exchange(
            baseUrl() + "/api/v1/admin/orders?export=true&segment=ALL",
            HttpMethod.GET,
            bearer(opsToken, null),
            Map.class);
    assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(export).get("status")).isEqualTo("PROCESSING");
    UUID jobId = UUID.fromString(String.valueOf(data(export).get("job_id")));
    var principal =
        new com.nammamedmate.security.MedmatePrincipal(
            OPS_ID,
            com.nammamedmate.security.AuthRole.ADMIN_OPERATIONS,
            null,
            com.nammamedmate.security.TokenScope.FULL,
            "j");
    String status = null;
    Map<String, Object> jobView = null;
    for (int i = 0; i < 120; i++) {
      jobView = adminOrders.exportJobStatus(principal, jobId);
      status = String.valueOf(jobView.get("status"));
      if ("READY".equals(status) || "FAILED".equals(status)) {
        break;
      }
      try {
        Thread.sleep(1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(status).isEqualTo("READY");
    assertThat(jobView.get("download_url")).isNotNull();
  }

  private void insertAdmin(UUID id, String name, String email, String role, String hash) {
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE',"
            + " false, 0, NOW(), NOW())",
        id,
        name,
        email,
        hash,
        role);
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
            + " false, ?, ?, ?::jsonb, 'tag', '+91-8022334498', NOW(), NOW())",
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
                    "it-admin-ord")),
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
}
