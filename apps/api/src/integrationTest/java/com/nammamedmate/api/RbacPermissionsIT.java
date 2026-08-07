package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

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

class RbacPermissionsIT extends AbstractApiIT {

  private static final String PASSWORD = "Passw0rd!";
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

  private static final UUID PHARMACY_ID = UUID.fromString("aaaaaaaa-0005-0005-0005-000000000001");
  private static final UUID OWNER_ID = UUID.fromString("bbbbbbbb-0005-0005-0005-000000000001");
  private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-0005-0005-0005-000000000002");
  private static final UUID OPS_ID = UUID.fromString("cccccccc-0005-0005-0005-000000000001");
  private static final UUID SUPPORT_ID = UUID.fromString("cccccccc-0005-0005-0005-000000000002");

  private static final String OWNER_ROLE_ID = "00000000-0000-0000-0001-000000000001";
  private static final String CASHIER_ROLE_ID = "00000000-0000-0000-0001-000000000003";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void seed() {
    flush("admin:rbac:*");
    flush("pharmacy:rbac:*");
    flush("pharmacy:ip:*");
    flush("admin:ip:*");

    jdbc.update(
        "DELETE FROM sessions WHERE user_id IN (?, ?, ?, ?)",
        OWNER_ID,
        STAFF_ID,
        OPS_ID,
        SUPPORT_ID);
    jdbc.update(
        "DELETE FROM pharmacy_staff_assignment WHERE staff_id IN (?, ?)", OWNER_ID, STAFF_ID);
    jdbc.update(
        "DELETE FROM pharmacy_roles WHERE pharmacy_id = ? OR created_by IN (?, ?)",
        PHARMACY_ID,
        OWNER_ID,
        STAFF_ID);
    jdbc.update("DELETE FROM pharmacy_staff WHERE id IN (?, ?)", OWNER_ID, STAFF_ID);
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE pharmacy_id = ?", PHARMACY_ID);
    jdbc.update("DELETE FROM pharmacies WHERE id = ?", PHARMACY_ID);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id IN (?, ?)", OPS_ID, SUPPORT_ID);
    jdbc.update("DELETE FROM admin_staff WHERE id IN (?, ?)", OPS_ID, SUPPORT_ID);

    String hash = ENCODER.encode(PASSWORD);
    jdbc.update(
        "INSERT INTO pharmacies (id, name, city, subscription_plan, code, created_at, updated_at)"
            + " VALUES (?, 'RBAC Pharmacy', 'Bengaluru', 'GROWTH', 'PHM-RBAC', NOW(), NOW())",
        PHARMACY_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Owner', 'owner-rbac@p.in',"
            + " ?, 'ACTIVE', 0, NOW(), NOW())",
        OWNER_ID,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff (id, name, email, password_hash, status,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Cashier',"
            + " 'cashier-rbac@p.in', ?, 'ACTIVE', 0, NOW(), NOW())",
        STAFF_ID,
        hash);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        OWNER_ID,
        PHARMACY_ID,
        OWNER_ROLE_ID);
    jdbc.update(
        "INSERT INTO pharmacy_staff_assignment (id, staff_id, pharmacy_id, role_id, is_active,"
            + " joined_at) VALUES (?, ?, ?, ?::uuid, true, NOW())",
        UUID.randomUUID(),
        STAFF_ID,
        PHARMACY_ID,
        CASHIER_ROLE_ID);

    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Ops', 'ops-rbac@test.in',"
            + " ?, 'admin_operations', 'ACTIVE', false, 0, NOW(), NOW())",
        OPS_ID,
        hash);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, 'Support',"
            + " 'support-rbac@test.in', ?, 'admin_support', 'ACTIVE', false, 0, NOW(), NOW())",
        SUPPORT_ID,
        hash);
  }

  @Test
  void adminOpsListsRolesAndFilteredPermissions() {
    String token = adminToken("ops-rbac@test.in");
    ResponseEntity<Map> roles = get("/api/v1/admin/roles", token);
    assertThat(roles.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data =
        (List<Map<String, Object>>) Objects.requireNonNull(roles.getBody()).get("data");
    assertThat(data).hasSize(5);
    assertThat(data.get(0).get("permissions")).isEqualTo(List.of("*:*"));

    ResponseEntity<Map> perms = get("/api/v1/admin/permissions?resource=orders", token);
    assertThat(perms.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orderPerms =
        (List<Map<String, Object>>) Objects.requireNonNull(perms.getBody()).get("data");
    assertThat(orderPerms).isNotEmpty();
    assertThat(orderPerms).allMatch(m -> "orders".equals(m.get("resource")));
  }

  @Test
  void adminSupportForbiddenOnPharmaciesSuspend() {
    String token = adminToken("support-rbac@test.in");
    ResponseEntity<Map> response =
        exchange(
            HttpMethod.POST, "/api/v1/admin/pharmacies/" + PHARMACY_ID + "/suspend", token, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("FORBIDDEN");
  }

  @Test
  void pharmacyOwnerManagesCustomRoles() {
    String ownerToken = pharmacyToken("owner-rbac@p.in");
    ResponseEntity<Map> created =
        exchange(
            HttpMethod.POST,
            "/api/v1/pharmacy/roles",
            ownerToken,
            Map.of(
                "name",
                "senior_pharmacist",
                "display_name",
                "Senior Pharmacist",
                "permissions",
                List.of("orders:fulfill", "inventory:*")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    @SuppressWarnings("unchecked")
    Map<String, Object> role =
        (Map<String, Object>) Objects.requireNonNull(created.getBody()).get("data");
    String roleId = role.get("id").toString();

    ResponseEntity<Map> updated =
        exchange(
            HttpMethod.PUT,
            "/api/v1/pharmacy/roles/" + roleId + "/permissions",
            ownerToken,
            Map.of("permissions", List.of("orders:read", "reports:read")));
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> updatedData =
        (Map<String, Object>) Objects.requireNonNull(updated.getBody()).get("data");
    assertThat(updatedData.get("permissions")).isEqualTo(List.of("orders:read", "reports:read"));

    String staffToken = pharmacyToken("cashier-rbac@p.in");
    ResponseEntity<Map> denied =
        exchange(
            HttpMethod.POST,
            "/api/v1/pharmacy/roles",
            staffToken,
            Map.of("name", "nope", "display_name", "Nope", "permissions", List.of("orders:read")));
    assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void deleteCustomRoleInUseReturnsRoleInUseThenSucceeds() {
    String ownerToken = pharmacyToken("owner-rbac@p.in");
    ResponseEntity<Map> created =
        exchange(
            HttpMethod.POST,
            "/api/v1/pharmacy/roles",
            ownerToken,
            Map.of(
                "name",
                "in_use_role",
                "display_name",
                "In Use",
                "permissions",
                List.of("orders:read")));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    @SuppressWarnings("unchecked")
    Map<String, Object> role =
        (Map<String, Object>) Objects.requireNonNull(created.getBody()).get("data");
    String roleId = role.get("id").toString();

    jdbc.update(
        "UPDATE pharmacy_staff_assignment SET role_id = ?::uuid WHERE staff_id = ?",
        roleId,
        STAFF_ID);

    ResponseEntity<Map> inUse =
        exchange(HttpMethod.DELETE, "/api/v1/pharmacy/roles/" + roleId, ownerToken, null);
    assertThat(inUse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(inUse.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo("ROLE_IN_USE");

    jdbc.update(
        "UPDATE pharmacy_staff_assignment SET role_id = ?::uuid WHERE staff_id = ?",
        CASHIER_ROLE_ID,
        STAFF_ID);

    ResponseEntity<Map> deleted =
        exchange(HttpMethod.DELETE, "/api/v1/pharmacy/roles/" + roleId, ownerToken, null);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  private String adminToken(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            new HttpEntity<>(Map.of("email", email, "password", PASSWORD), jsonHeaders()),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    return data.get("access_token").toString();
  }

  private String pharmacyToken(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/pharmacy/login",
            new HttpEntity<>(Map.of("identifier", email, "password", PASSWORD), jsonHeaders()),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    return data.get("access_token").toString();
  }

  private ResponseEntity<Map> get(String path, String token) {
    return exchange(HttpMethod.GET, path, token, null);
  }

  private ResponseEntity<Map> exchange(HttpMethod method, String path, String token, Object body) {
    HttpHeaders headers = jsonHeaders();
    headers.setBearerAuth(token);
    return rest.exchange(baseUrl() + path, method, new HttpEntity<>(body, headers), Map.class);
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private void flush(String pattern) {
    var keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }
}
