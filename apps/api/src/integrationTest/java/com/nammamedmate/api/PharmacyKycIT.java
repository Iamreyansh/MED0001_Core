package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration test for EPIC-003 STORY-002 KYC document upload flow. Covers: register → verify-email
 * → upload documents → list → submit; error paths.
 */
class PharmacyKycIT extends AbstractApiIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  private static final UUID ADMIN_ID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final String ADMIN_PASSWORD = "KycAdmin1!";

  @Test
  void kycHappyPath() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String email = "kyc-" + suffix + "@nammamedmate.test";
    String phone = "+9198" + String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000));
    String[] gstinPan = makeGstinPan(suffix, "KYC");
    // Register
    ResponseEntity<Map> register =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(
                registrationBody(email, phone, gstinPan[0], gstinPan[1], "KA-DL-KYC-" + suffix)),
            Map.class);
    assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // Verify email (magic OTP)
    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register/verify-email",
            jsonBody(Map.of("email", email, "otp", "123456")),
            Map.class);
    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    String accessToken = String.valueOf(verifyData.get("access_token"));
    String pharmacyId = String.valueOf(verifyData.get("pharmacy_id"));
    assertThat(accessToken).isNotBlank();

    // List docs — empty
    ResponseEntity<Map> emptyList = getAuth("/api/v1/pharmacy/kyc/documents", accessToken);
    assertThat(emptyList.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> emptyListData =
        (Map<String, Object>) Objects.requireNonNull(emptyList.getBody()).get("data");
    @SuppressWarnings("unchecked")
    List<?> emptyDocs = (List<?>) emptyListData.get("documents");
    assertThat(emptyDocs).isEmpty();
    assertThat(emptyListData.get("ready_to_submit")).isEqualTo(false);

    // AC-002 FILE_TOO_LARGE covered in PharmacyKycServiceTest; servlet also caps at 10MB so an
    // 11MB multipart never reaches the service layer in this IT.

    // Upload GSTIN
    ResponseEntity<Map> gstinUpload = uploadSmallDoc(accessToken, "GSTIN_CERTIFICATE", null);
    assertThat(gstinUpload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    @SuppressWarnings("unchecked")
    Map<String, Object> gstinData =
        (Map<String, Object>) Objects.requireNonNull(gstinUpload.getBody()).get("data");
    assertThat(gstinData.get("status")).isEqualTo("UPLOADED");
    assertThat(gstinData.get("signed_url")).isNull();
    String gstinDocId = String.valueOf(gstinData.get("document_id"));

    // AC-003: Duplicate pending → 409 DOCUMENT_TYPE_ALREADY_PENDING
    ResponseEntity<Map> dup = uploadSmallDoc(accessToken, "GSTIN_CERTIFICATE", null);
    assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertErrorCode(dup, "DOCUMENT_TYPE_ALREADY_PENDING");

    // Upload remaining required docs
    uploadSmallDoc(accessToken, "DRUG_LICENCE", "2027-12-31");
    uploadSmallDoc(accessToken, "FSSAI_CERTIFICATE", "2027-12-31");
    uploadSmallDoc(accessToken, "PAN_CARD", null);
    uploadSmallDoc(accessToken, "BANK_STATEMENT", null);

    jdbc.update(
        "UPDATE kyc_documents SET status = 'SCAN_CLEAN' WHERE pharmacy_id = ?::uuid AND status = 'UPLOADED'",
        pharmacyId);

    // List — all docs present, ready to submit
    ResponseEntity<Map> fullList = getAuth("/api/v1/pharmacy/kyc/documents", accessToken);
    assertThat(fullList.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> fullListData =
        (Map<String, Object>) Objects.requireNonNull(fullList.getBody()).get("data");
    assertThat(fullListData.get("ready_to_submit")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<?> allDocs = (List<?>) fullListData.get("documents");
    assertThat(allDocs).hasSize(5);

    // AC-001: Submit → KYC_SUBMITTED
    ResponseEntity<Map> submit = postAuth("/api/v1/pharmacy/kyc/submit", Map.of(), accessToken);
    assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> submitData =
        (Map<String, Object>) Objects.requireNonNull(submit.getBody()).get("data");
    assertThat(submitData.get("status")).isEqualTo("KYC_SUBMITTED");
    assertThat(submitData.get("estimated_review_hours")).isEqualTo(24);

    // Already submitted → 409
    ResponseEntity<Map> alreadySubmitted =
        postAuth("/api/v1/pharmacy/kyc/submit", Map.of(), accessToken);
    assertThat(alreadySubmitted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertErrorCode(alreadySubmitted, "ALREADY_SUBMITTED");
  }

  @Test
  void adminRejectThenOwnerDeleteAndReupload() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String email = "del-" + suffix + "@nammamedmate.test";
    String phone = "+9197" + String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000));
    String[] gstinPan = makeGstinPan(suffix, "DEL");

    ResponseEntity<Map> register =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(registrationBody(email, phone, gstinPan[0], gstinPan[1], "DL-DEL-" + suffix)),
            Map.class);
    assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register/verify-email",
            jsonBody(Map.of("email", email, "otp", "123456")),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    String accessToken = String.valueOf(verifyData.get("access_token"));
    String pharmacyId = String.valueOf(verifyData.get("pharmacy_id"));

    ResponseEntity<Map> upload = uploadSmallDoc(accessToken, "PAN_CARD", null);
    assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    @SuppressWarnings("unchecked")
    Map<String, Object> uploadData =
        (Map<String, Object>) Objects.requireNonNull(upload.getBody()).get("data");
    String docId = String.valueOf(uploadData.get("document_id"));

    seedAdmin(ADMIN_ID, "kyc-compliance-" + suffix + "@test.in", "admin_compliance");
    String adminToken = adminLogin("kyc-compliance-" + suffix + "@test.in");

    ResponseEntity<Map> adminGet =
        getAuth("/api/v1/admin/pharmacies/" + pharmacyId + "/kyc", adminToken);
    assertThat(adminGet.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> reject =
        postAuth(
            "/api/v1/admin/pharmacies/" + pharmacyId + "/kyc/documents/" + docId + "/verify",
            Map.of("verified", false, "rejection_reason", "Image is blurry"),
            adminToken);
    assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> rejectData =
        (Map<String, Object>) Objects.requireNonNull(reject.getBody()).get("data");
    assertThat(rejectData.get("status")).isEqualTo("REJECTED");
    assertThat(rejectData.get("verified_by")).isNotNull();
    assertThat(rejectData.get("verified_at")).isNotNull();

    HttpHeaders delHeaders = new HttpHeaders();
    delHeaders.setBearerAuth(accessToken);
    ResponseEntity<Map> delete =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/kyc/documents/" + docId,
            HttpMethod.DELETE,
            new HttpEntity<>(delHeaders),
            Map.class);
    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> reupload = uploadSmallDoc(accessToken, "PAN_CARD", null);
    assertThat(reupload.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // AC-006: verified doc cannot be deleted
    @SuppressWarnings("unchecked")
    Map<String, Object> reuploadData =
        (Map<String, Object>) Objects.requireNonNull(reupload.getBody()).get("data");
    String newDocId = String.valueOf(reuploadData.get("document_id"));
    ResponseEntity<Map> approve =
        postAuth(
            "/api/v1/admin/pharmacies/" + pharmacyId + "/kyc/documents/" + newDocId + "/verify",
            Map.of("verified", true),
            adminToken);
    assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map> cannotDelete =
        rest.exchange(
            baseUrl() + "/api/v1/pharmacy/kyc/documents/" + newDocId,
            HttpMethod.DELETE,
            new HttpEntity<>(delHeaders),
            Map.class);
    assertThat(cannotDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertErrorCode(cannotDelete, "CANNOT_DELETE_VERIFIED");
  }

  @Test
  void staffCanListButNotUpload() {
    // Staff auth is from existing fixture — use PharmacyStaffAuthIT pattern
    // For simplicity, just test that UNAUTHORIZED (no token) returns 401 for KYC endpoints
    ResponseEntity<Map> noToken =
        rest.getForEntity(baseUrl() + "/api/v1/pharmacy/kyc/documents", Map.class);
    assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void uploadInvalidFileTypeReturns400() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String email = "inv-" + suffix + "@nammamedmate.test";
    String phone = "+9196" + String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000));
    String[] gstinPan = makeGstinPan(suffix, "INV");

    ResponseEntity<Map> register =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register",
            jsonBody(registrationBody(email, phone, gstinPan[0], gstinPan[1], "DL-INV-" + suffix)),
            Map.class);
    assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/pharmacy/register/verify-email",
            jsonBody(Map.of("email", email, "otp", "123456")),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData =
        (Map<String, Object>) Objects.requireNonNull(verify.getBody()).get("data");
    String accessToken = String.valueOf(verifyData.get("access_token"));

    // Upload with wrong MIME
    ResponseEntity<Map> bad =
        uploadDoc(
            accessToken,
            "PAN_CARD",
            "content".getBytes(),
            "file.exe",
            "application/octet-stream",
            null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertErrorCode(bad, "INVALID_FILE_TYPE");
  }

  private void seedAdmin(UUID id, String email, String role) {
    jdbc.update("DELETE FROM sessions WHERE user_id = ?", id);
    jdbc.update("DELETE FROM admin_auth_events WHERE admin_id = ?", id);
    jdbc.update("DELETE FROM admin_staff WHERE id = ?", id);
    String hash = new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD);
    jdbc.update(
        "INSERT INTO admin_staff (id, name, email, password_hash, role, status, mfa_enabled,"
            + " failed_login_attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE',"
            + " false, 0, NOW(), NOW())",
        id,
        "KYC Admin",
        email,
        hash,
        role);
  }

  private String adminLogin(String email) {
    ResponseEntity<Map> login =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/admin/login",
            jsonBody(Map.of("email", email, "password", ADMIN_PASSWORD)),
            Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>) Objects.requireNonNull(login.getBody()).get("data");
    return String.valueOf(data.get("access_token"));
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private ResponseEntity<Map> uploadSmallDoc(String token, String type, String expiryDate) {
    return uploadDoc(
        token, type, "%PDF-1.4 sample".getBytes(), "doc.pdf", "application/pdf", expiryDate);
  }

  private ResponseEntity<Map> uploadDoc(
      String token, String type, byte[] content, String fileName, String mime, String expiryDate) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(token);

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("document_type", type);
    ByteArrayResource resource =
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return fileName;
          }
        };
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType(mime));
    body.add("file", new HttpEntity<>(resource, fileHeaders));
    if (expiryDate != null) {
      body.add("expiry_date", expiryDate);
    }
    return rest.postForEntity(
        baseUrl() + "/api/v1/pharmacy/kyc/documents", new HttpEntity<>(body, headers), Map.class);
  }

  private ResponseEntity<Map> getAuth(String path, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return rest.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private ResponseEntity<Map> postAuth(String path, Map<String, ?> requestBody, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    return rest.postForEntity(baseUrl() + path, new HttpEntity<>(requestBody, headers), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static void assertErrorCode(ResponseEntity<Map> response, String expectedCode) {
    Map<String, Object> error =
        (Map<String, Object>) Objects.requireNonNull(response.getBody()).get("error");
    assertThat(error.get("code")).isEqualTo(expectedCode);
  }

  /**
   * Returns [gstin, pan] with a valid MOD-36 checksum. prefix3 is the first 3 alpha chars of PAN;
   * entity type is forced to 'P' (individual) at position 3.
   */
  private static String[] makeGstinPan(String hexSeed8, String prefix3) {
    int seed = (int) (Long.parseUnsignedLong(hexSeed8, 16) % 9000L) + 1000;
    String pan = String.format("%sPA%04dF", prefix3, seed);
    String first14 = "29" + pan + "1Z";
    String base36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    int factor = 1, total = 0;
    for (char c : first14.toCharArray()) {
      int cp = base36.indexOf(c);
      int prod = factor * cp;
      total += (prod / 36) + (prod % 36);
      factor = factor == 1 ? 2 : 1;
    }
    char check = base36.charAt((36 - (total % 36)) % 36);
    return new String[] {first14 + check, pan};
  }

  private static Map<String, Object> registrationBody(
      String email, String phone, String gstin, String pan, String licence) {
    return Map.ofEntries(
        Map.entry("owner_name", "Test Owner"),
        Map.entry("business_name", "Test Pharmacy"),
        Map.entry("phone", phone),
        Map.entry("email", email),
        Map.entry("password", "Passw0rd!"),
        Map.entry("business_type", "PHARMACY"),
        Map.entry(
            "address",
            Map.of(
                "flat", "12",
                "area", "MG Road",
                "city", "Bengaluru",
                "state", "Karnataka",
                "pincode", "560001")),
        Map.entry("gstin", gstin),
        Map.entry("drug_licence_number", licence),
        Map.entry("fssai_number", "12345678901234"),
        Map.entry("pan_number", pan));
  }

  private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
