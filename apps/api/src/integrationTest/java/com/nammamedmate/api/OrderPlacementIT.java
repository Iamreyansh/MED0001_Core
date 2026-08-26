package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import com.nammamedmate.order.application.port.out.CashfreePaymentPort;
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

class OrderPlacementIT extends AbstractApiIT {

  private static final UUID OPS_ID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-eeeeeeee0104");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0013-0013-0013-000000000001");
  private static final UUID STAFF1 = UUID.fromString("bbbbbbbb-0013-0013-0013-000000000001");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String OPS_EMAIL = "order-ops@test.in";
  private static final String OWNER_EMAIL = "order-owner@test.in";
  private static final String PASSWORD = "OrderTest1!";
  private static final String CUSTOMER_PHONE = "+919999900053";
  private static final String CUSTOMER_PHONE_B = "+919999900054";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CashfreePaymentPort cashfree;

  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void seed() {
    purgeOrdersForPhones(CUSTOMER_PHONE, CUSTOMER_PHONE_B);
    jdbc.update(
        "DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE phone IN (?, ?))",
        CUSTOMER_PHONE,
        CUSTOMER_PHONE_B);
    jdbc.update(
        "UPDATE customers SET default_address_id = NULL WHERE phone IN (?, ?)",
        CUSTOMER_PHONE,
        CUSTOMER_PHONE_B);
    jdbc.update(
        "DELETE FROM customer_addresses WHERE customer_id IN (SELECT id FROM customers WHERE phone IN (?, ?))",
        CUSTOMER_PHONE,
        CUSTOMER_PHONE_B);
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id = ?", PH1);
    jdbc.update("DELETE FROM pharmacy_staff_assignment WHERE staff_id = ?", STAFF1);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id = ? OR email = ?", STAFF1, OWNER_EMAIL);
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", OPS_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id = ? OR email = ?", OPS_ID, OPS_EMAIL);
    jdbc.update("DELETE FROM medicine_master WHERE name LIKE 'OrderIT%'");
    jdbc.update(
        "DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id IN (SELECT id FROM pharmacies WHERE code = 'PHM-O1' OR id = ?)",
        PH1);
    jdbc.update(
        "DELETE FROM pharmacy_directory_metrics WHERE pharmacy_id IN (SELECT id FROM pharmacies WHERE code = 'PHM-O1' OR id = ?)",
        PH1);
    jdbc.update("DELETE FROM pharmacies WHERE code = 'PHM-O1' OR id = ?", PH1);

    String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Order Ops', ?,"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        OPS_EMAIL,
        hash);

    insertPharmacy(PH1, "Order Sai", "PHM-O1", 12.9350, 77.6130);
    jdbc.update(
        "INSERT INTO pharmacy_directory_metrics (pharmacy_id, rating, review_count, fill_rate_pct,"
            + " metrics_as_of, updated_at) VALUES (?, 4.60, 100, 95.00, NOW(), NOW())",
        PH1);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Order Owner', ?, ?,"
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
  void placeCodGetConfirmUpiAndCartCheckedOut() {
    String opsToken = adminLogin(OPS_EMAIL);
    String customerToken = customerLogin(CUSTOMER_PHONE);
    String pharmacyToken = pharmacyLogin();

    String medOtc = createMedicine(opsToken, "OrderIT Para", "OTC", 85.00);
    String medRx = createMedicine(opsToken, "OrderIT Met Rx", "H", 85.00);
    mapMedicine(pharmacyToken, medOtc, 85.00, 200);
    mapMedicine(pharmacyToken, medRx, 85.00, 200);

    // AC1: Rx without prescription
    ResponseEntity<Map> addRx =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medRx, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(addRx.getStatusCode()).isEqualTo(HttpStatus.OK);
    String rxCartId = String.valueOf(data(addRx).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> rxPlace =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", rxCartId, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(rxPlace.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(rxPlace)).isEqualTo("PRESCRIPTION_REQUIRED");

    // clear and build OTC cart for COD
    rest.exchange(
        baseUrl() + "/api/v1/cart", HttpMethod.DELETE, bearer(customerToken, null), Map.class);
    ResponseEntity<Map> add =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medOtc, "quantity", 3, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(add.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cartId = String.valueOf(data(add).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);

    // AC2: stock drop
    jdbc.update(
        "UPDATE pharmacy_catalogue_mapping SET stock_quantity = 0 WHERE pharmacy_id = ? AND"
            + " master_medicine_id = ?::uuid",
        PH1,
        UUID.fromString(medOtc));
    ResponseEntity<Map> oos =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", cartId, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(oos.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(oos)).isEqualTo("ITEMS_OUT_OF_STOCK");
    jdbc.update(
        "UPDATE pharmacy_catalogue_mapping SET stock_quantity = 200 WHERE pharmacy_id = ? AND"
            + " master_medicine_id = ?::uuid",
        PH1,
        UUID.fromString(medOtc));

    // AC3 + AC8: COD place → CHECKED_OUT
    String idemCod = UUID.randomUUID().toString();
    ResponseEntity<Map> placed =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of(
                    "cart_id",
                    cartId,
                    "payment_method",
                    "COD",
                    "delivery_instructions",
                    "Leave at door"),
                idemCod),
            Map.class);
    assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> order = data(placed);
    assertThat(order.get("status")).isEqualTo("PENDING_ACCEPTANCE");
    @SuppressWarnings("unchecked")
    Map<String, Object> payment = (Map<String, Object>) order.get("payment");
    assertThat(payment.get("status")).isEqualTo("PENDING_COLLECTION");
    String orderId = String.valueOf(order.get("order_id"));

    ResponseEntity<Map> cartAfter =
        rest.exchange(
            baseUrl() + "/api/v1/cart", HttpMethod.GET, bearer(customerToken, null), Map.class);
    assertThat(data(cartAfter).get("status")).isEqualTo("ACTIVE");
    assertThat(((List<?>) data(cartAfter).get("items"))).isEmpty();
    String status =
        jdbc.queryForObject("SELECT status FROM carts WHERE id = ?::uuid", String.class, cartId);
    assertThat(status).isEqualTo("CHECKED_OUT");

    ResponseEntity<Map> get =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + orderId,
            HttpMethod.GET,
            bearer(customerToken, null),
            Map.class);
    assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(get).get("order_number")).isEqualTo(order.get("order_number"));

    // UPI flow: new cart
    ResponseEntity<Map> add2 =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                customerToken,
                Map.of("medicine_id", medOtc, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    String cart2 = String.valueOf(data(add2).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(customerToken, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> upi =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("cart_id", cart2, "payment_method", "UPI"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(upi.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> upiOrder = data(upi);
    assertThat(upiOrder.get("status")).isEqualTo("PAYMENT_PENDING");
    String upiOrderId = String.valueOf(upiOrder.get("order_id"));
    @SuppressWarnings("unchecked")
    Map<String, Object> upiPay = (Map<String, Object>) upiOrder.get("payment");
    String rzOrderId = String.valueOf(upiPay.get("gateway_order_id"));

    // AC4 invalid signature
    ResponseEntity<Map> badSig =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + upiOrderId + "/payment/confirm",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("payment_id", "pay_x", "payment_signature", "bad"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(badSig.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(badSig)).isEqualTo("PAYMENT_SIGNATURE_INVALID");

    // AC5 idempotent confirm
    String paymentId = "pay_it_confirm";
    String sig = cashfree.signPayment(rzOrderId, paymentId);
    String idemConfirm = UUID.randomUUID().toString();
    ResponseEntity<Map> confirm1 =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + upiOrderId + "/payment/confirm",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("payment_id", paymentId, "payment_signature", sig),
                idemConfirm),
            Map.class);
    assertThat(confirm1.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(confirm1).get("status")).isEqualTo("PENDING_ACCEPTANCE");
    ResponseEntity<Map> confirm2 =
        rest.exchange(
            baseUrl() + "/api/v1/orders/" + upiOrderId + "/payment/confirm",
            HttpMethod.POST,
            bearerIdem(
                customerToken,
                Map.of("payment_id", paymentId, "payment_signature", sig),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(confirm2.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(confirm2).get("status")).isEqualTo("PENDING_ACCEPTANCE");
  }

  @Test
  void lastSellableUnitBlocksSecondCustomer() {
    String opsToken = adminLogin(OPS_EMAIL);
    String pharmacyToken = pharmacyLogin();
    String tokenA = customerLogin(CUSTOMER_PHONE);
    String tokenB = customerLogin(CUSTOMER_PHONE_B);
    UUID customerB =
        jdbc.queryForObject(
            "SELECT id FROM customers WHERE phone = ?", UUID.class, CUSTOMER_PHONE_B);
    UUID addressB = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO customer_addresses (
          id, customer_id, label, flat_building, area_locality, city, state, pincode,
          latitude, longitude, is_default, created_at, updated_at
        ) VALUES (?, ?, 'Home', '12', 'Koramangala', 'Bengaluru', 'KA', '560034',
          12.9345, 77.6125, true, NOW(), NOW())
        """,
        addressB,
        customerB);
    jdbc.update("UPDATE customers SET default_address_id = ? WHERE id = ?", addressB, customerB);

    String med = createMedicine(opsToken, "OrderIT LastUnit", "OTC", 85.00);
    mapMedicine(pharmacyToken, med, 85.00, 1);

    ResponseEntity<Map> addA =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                tokenA, Map.of("medicine_id", med, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(addA.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cartA = String.valueOf(data(addA).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(tokenA, Map.of("address_id", addressId.toString())),
        Map.class);
    ResponseEntity<Map> addB =
        rest.exchange(
            baseUrl() + "/api/v1/cart/items",
            HttpMethod.POST,
            bearer(
                tokenB, Map.of("medicine_id", med, "quantity", 1, "lat", 12.9345, "lng", 77.6125)),
            Map.class);
    assertThat(addB.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cartB = String.valueOf(data(addB).get("cart_id"));
    rest.exchange(
        baseUrl() + "/api/v1/cart/address",
        HttpMethod.POST,
        bearer(tokenB, Map.of("address_id", addressB.toString())),
        Map.class);

    ResponseEntity<Map> placedA =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                tokenA,
                Map.of("cart_id", cartA, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(placedA.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> placedB =
        rest.exchange(
            baseUrl() + "/api/v1/orders",
            HttpMethod.POST,
            bearerIdem(
                tokenB,
                Map.of("cart_id", cartB, "payment_method", "COD"),
                UUID.randomUUID().toString()),
            Map.class);
    assertThat(placedB.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(errorCode(placedB)).isEqualTo("ITEMS_OUT_OF_STOCK");
  }

  private void purgeOrdersForPhones(String... phones) {
    for (String phone : phones) {
      jdbc.update(
          "DELETE FROM payment WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN"
              + " (SELECT id FROM customers WHERE phone = ?))",
          phone);
      jdbc.update(
          "DELETE FROM inventory_reservation WHERE order_id IN (SELECT id FROM orders WHERE"
              + " customer_id IN (SELECT id FROM customers WHERE phone = ?))",
          phone);
      jdbc.update(
          "DELETE FROM delivery_fee_snapshots WHERE order_id IN (SELECT id FROM orders WHERE"
              + " customer_id IN (SELECT id FROM customers WHERE phone = ?))",
          phone);
      jdbc.update(
          "DELETE FROM order_status_event WHERE order_id IN (SELECT id FROM orders WHERE"
              + " customer_id IN (SELECT id FROM customers WHERE phone = ?))",
          phone);
      jdbc.update(
          "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE phone = ?)",
          phone);
    }
  }

  private void insertPharmacy(UUID id, String name, String code, double lat, double lng) {
    jdbc.update(
        "INSERT INTO pharmacies (id, name, business_name, city, subscription_plan, code, status,"
            + " is_online, admin_forced_offline, latitude, longitude, address, tagline, phone,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'Bengaluru', 'GROWTH', ?, 'ACTIVE', true,"
            + " false, ?, ?, ?::jsonb, 'tag', '+91-8022330104', NOW(), NOW())",
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
                    "it-order")),
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
