package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.adapter.out.client.StubDigiLockerClient;
import com.nammamedmate.integration.adapter.out.client.StubDrugRegistryClient;
import com.nammamedmate.integration.adapter.out.client.StubFssaiClient;
import com.nammamedmate.integration.adapter.out.client.StubGstnClient;
import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.integration.domain.GovernmentApiTypes;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernmentApiServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final String VALID_GSTIN = "29ABCDE1234F1ZW";
  private static final String BAD_CHECKSUM = "29ABCDE1234F1Z0";
  private static final String NOT_FOUND_GSTIN = "29AAAAA0000A1ZY";
  private static final String DOWN_GSTIN = "29AAAAA9999A1ZG";

  private InMemoryStores.GovCache cache;
  private InMemoryStores.GovLogs logs;
  private List<String> events;
  private AtomicInteger gstnCalls;
  private GovernmentApiService service;

  @BeforeEach
  void setUp() {
    cache = new InMemoryStores.GovCache();
    logs = new InMemoryStores.GovLogs();
    events = new ArrayList<>();
    gstnCalls = new AtomicInteger();
    StubGstnClient stub = new StubGstnClient();
    GstnClientPort counting =
        gstin -> {
          gstnCalls.incrementAndGet();
          return stub.verify(gstin);
        };
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new GovernmentApiService(
            counting,
            new StubDrugRegistryClient(clock),
            new StubFssaiClient(clock),
            new StubDigiLockerClient(),
            cache,
            logs,
            (type, agg, id, payload) -> events.add(type),
            clock);
  }

  @Test
  void ac001_invalidGstinChecksumDoesNotCallApi() {
    assertThatThrownBy(() -> service.verifyGstin(BAD_CHECKSUM, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GSTIN_FORMAT");
    assertThat(gstnCalls.get()).isZero();
    assertThat(logs.all()).isNotEmpty();
    assertThat(logs.all().get(0).resultStatus()).isEqualTo("INVALID");
  }

  @Test
  void ac002_reverifyWithin7DaysIsCacheHit() {
    Map<String, Object> first = service.verifyGstin(VALID_GSTIN, "PHARMACY", null);
    assertThat(first.get("cache_hit")).isEqualTo(false);
    assertThat(gstnCalls.get()).isEqualTo(1);

    Map<String, Object> second = service.verifyGstin(VALID_GSTIN, "PHARMACY", null);
    assertThat(second.get("cache_hit")).isEqualTo(true);
    assertThat(gstnCalls.get()).isEqualTo(1);
    assertThat(logs.all().get(1).wasCacheHit()).isTrue();
  }

  @Test
  void ac003_expiredDrugLicence() {
    Map<String, Object> data =
        service.verifyDrugLicence("KA/DRUG/EXPIRED/0042", "Karnataka", "RETAIL", null, null);
    assertThat(data.get("is_expired")).isEqualTo(true);
    assertThat(data.get("status")).isEqualTo("EXPIRED");
    assertThat(data.get("valid")).isEqualTo(false);
  }

  @Test
  void ac004_digilockerInitiateReturnsAuthUrl() {
    UUID entityId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    Map<String, Object> data =
        service.initiateDigiLocker(
            "+919876543210",
            "PHARMACY_KYC",
            entityId,
            "https://app.nammamedmate.in/kyc/digilocker/callback");
    assertThat(data.get("auth_url").toString())
        .startsWith("https://api.digitallocker.gov.in/public/oauth2/1/authorize");
    assertThat(data.get("state")).isNotNull();
    assertThat(data.get("expires_in_seconds")).isEqualTo(600);
  }

  @Test
  void ac005_digilockerCallbackInvalidState() {
    assertThatThrownBy(() -> service.digiLockerCallback("code", "bad-state"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void ac006_licenceExpiringWithin30DaysPublishesRegisterDue() {
    UUID pharmacyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    service.verifyDrugLicence(
        "KA/DRUG/EXPIRING/0042", "Karnataka", "RETAIL", "PHARMACY", pharmacyId);
    assertThat(events).contains("register_due");
  }

  @Test
  void ac007_unknownFssaiNotFound() {
    assertThatThrownBy(() -> service.verifyFssai("UNKNOWN-10019011001234", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FSSAI_LICENCE_NOT_FOUND");
  }

  @Test
  void ac008_callsLoggedWithoutCredentialsOrAadhaar() {
    UUID entityId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    Map<String, Object> init =
        service.initiateDigiLocker(
            "+919876543210", "PHARMACY_KYC", entityId, "https://app.example/callback");
    service.digiLockerCallback("auth_code", init.get("state").toString());
    service.verifyGstin(VALID_GSTIN, "PHARMACY", entityId);

    assertThat(logs.all()).isNotEmpty();
    assertThat(logs.all().stream().map(l -> l.apiType()).toList())
        .contains(GovernmentApiTypes.DIGILOCKER, GovernmentApiTypes.GSTN);
    for (var entry : logs.all()) {
      assertThat(entry.identifier()).doesNotContain("9876543210");
      assertThat(entry.identifier()).doesNotContainIgnoringCase("aadhaar");
      assertThat(entry.identifier()).doesNotContain("secret");
      assertThat(entry.identifier()).doesNotContain("api-key");
    }
  }

  @Test
  void gstnRateLimitReturns429() {
    service.drainGstnTokens();
    assertThatThrownBy(() -> service.verifyGstin(VALID_GSTIN, null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("GSTN_RATE_LIMIT");
              assertThat(app.httpStatus()).isEqualTo(429);
              assertThat(app.retryAfterSeconds()).isNotNull();
            });
  }

  @Test
  void gstnNotFoundAndUnavailable() {
    assertThatThrownBy(() -> service.verifyGstin(NOT_FOUND_GSTIN, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GSTIN_NOT_FOUND");
    assertThatThrownBy(() -> service.verifyGstin(DOWN_GSTIN, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GSTN_API_UNAVAILABLE");
  }

  @Test
  void digilockerCallbackHappyPath() {
    UUID entityId = UUID.randomUUID();
    Map<String, Object> init =
        service.initiateDigiLocker("+9198", "PHARMACY_KYC", entityId, "https://cb");
    Map<String, Object> cb = service.digiLockerCallback("code-1", init.get("state").toString());
    assertThat(cb.get("aadhaar_verified")).isEqualTo(true);
    assertThat(cb.get("entity_id")).isEqualTo(entityId.toString());
    assertThat(cb.toString()).doesNotContain("XXXX");
  }

  @Test
  void drugAsyncThenPoll() {
    Map<String, Object> pending =
        service.verifyDrugLicence("OD/DRUG/2019/1", "Odisha", "RETAIL", null, null);
    assertThat(pending.get("status")).isEqualTo("PENDING");
    UUID id = UUID.fromString(pending.get("verification_id").toString());
    Map<String, Object> done = service.getDrugVerification(id);
    assertThat(done.get("status")).isIn("ACTIVE", "EXPIRED", "MANUAL_REVIEW_REQUIRED");
  }

  @Test
  void drugManualReviewAndFssaiManual() {
    assertThat(
            service
                .verifyDrugLicence("KA/DRUG/DOWN/1", "Karnataka", null, null, null)
                .get("status"))
        .isEqualTo("MANUAL_REVIEW_REQUIRED");
    assertThat(service.verifyFssai("DOWN-10019011001234", null, null).get("status"))
        .isEqualTo("MANUAL_REVIEW_REQUIRED");
  }

  @Test
  void fssaiHappyAndCache() {
    Map<String, Object> first = service.verifyFssai("10019011001234", null, null);
    assertThat(first.get("valid")).isEqualTo(true);
    Map<String, Object> second = service.verifyFssai("10019011001234", null, null);
    assertThat(second.get("cache_hit")).isEqualTo(true);
  }
}
