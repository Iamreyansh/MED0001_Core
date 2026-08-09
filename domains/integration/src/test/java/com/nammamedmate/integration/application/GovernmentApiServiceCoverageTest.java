package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.adapter.out.client.StubDigiLockerClient;
import com.nammamedmate.integration.adapter.out.client.StubDrugRegistryClient;
import com.nammamedmate.integration.adapter.out.client.StubFssaiClient;
import com.nammamedmate.integration.adapter.out.client.StubGstnClient;
import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import com.nammamedmate.integration.application.port.out.DrugRegistryClientPort;
import com.nammamedmate.integration.application.port.out.FssaiClientPort;
import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GovernmentApiServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private GovernmentApiService service(
      GstnClientPort gstn,
      DrugRegistryClientPort drug,
      FssaiClientPort fssai,
      DigiLockerClientPort digi) {
    return new GovernmentApiService(
        gstn,
        drug,
        fssai,
        digi,
        new InMemoryStores.GovCache(),
        new InMemoryStores.GovLogs(),
        (t, a, i, p) -> {},
        CLOCK);
  }

  @Test
  void validationBranches() {
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient());
    assertThatThrownBy(() -> svc.initiateDigiLocker("p", "x", null, "https://cb"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.initiateDigiLocker("p", "x", UUID.randomUUID(), " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.getDrugVerification(UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VERIFICATION_NOT_FOUND");
  }

  @Test
  void drugClientThrowsBecomesManualReview() {
    DrugRegistryClientPort drug = mock(DrugRegistryClientPort.class);
    when(drug.verify(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("down"));
    GovernmentApiService svc =
        service(new StubGstnClient(), drug, new StubFssaiClient(CLOCK), new StubDigiLockerClient());
    assertThat(svc.verifyDrugLicence("KA/1", "Karnataka", "RETAIL", null, null).get("status"))
        .isEqualTo("MANUAL_REVIEW_REQUIRED");
  }

  @Test
  void fssaiClientThrowsBecomesManualReview() {
    FssaiClientPort fssai = mock(FssaiClientPort.class);
    when(fssai.verify(anyString())).thenThrow(new RuntimeException("down"));
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            fssai,
            new StubDigiLockerClient());
    assertThat(svc.verifyFssai("1001", null, null).get("status"))
        .isEqualTo("MANUAL_REVIEW_REQUIRED");
  }

  @Test
  void drugCacheHitAndRegisterDueWithoutEntity() {
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient());
    svc.verifyDrugLicence("KA/DRUG/OK/1", "KA", "RETAIL", null, null);
    Map<String, Object> cached = svc.verifyDrugLicence("KA/DRUG/OK/1", "KA", "RETAIL", null, null);
    assertThat(cached.get("cache_hit")).isEqualTo(true);
    svc.verifyDrugLicence("KA/DRUG/EXPIRING/2", "MH", "RETAIL", null, null);
  }

  @Test
  void pollManualReviewFromDownLicence() {
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient());
    Map<String, Object> pending =
        svc.verifyDrugLicence("OD/DRUG/DOWN/1", "Odisha", "RETAIL", null, null);
    UUID id = UUID.fromString(pending.get("verification_id").toString());
    assertThat(svc.getDrugVerification(id).get("status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
  }

  @Test
  void forceSyncExpiredOnPoll() {
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient());
    Map<String, Object> pending =
        svc.verifyDrugLicence("OD/DRUG/EXPIRED/1", "Odisha", "RETAIL", null, null);
    UUID id = UUID.fromString(pending.get("verification_id").toString());
    Map<String, Object> done = svc.getDrugVerification(id);
    assertThat(done.get("is_expired")).isEqualTo(true);
  }

  @Test
  void digiLockerBlankCodeAndGstnAppExceptionPath() {
    DigiLockerClientPort digi = mock(DigiLockerClientPort.class);
    when(digi.buildAuthorizeUrl(any(), any()))
        .thenReturn(new DigiLockerClientPort.AuthUrl("https://a", "st", 600));
    when(digi.exchangeCode(any()))
        .thenReturn(
            new DigiLockerClientPort.DigiLockerDocuments(
                true, "N", LocalDate.of(1990, 1, 1), "addr", List.of("AADHAAR")));
    GstnClientPort gstn = mock(GstnClientPort.class);
    when(gstn.verify(anyString())).thenThrow(new AppException("GSTN_API_UNAVAILABLE", "down", 503));
    GovernmentApiService svc =
        service(gstn, new StubDrugRegistryClient(CLOCK), new StubFssaiClient(CLOCK), digi);
    UUID entity = UUID.randomUUID();
    Map<String, Object> init = svc.initiateDigiLocker(null, null, entity, "https://cb");
    assertThat(svc.digiLockerCallback("c", init.get("state").toString()).get("aadhaar_verified"))
        .isEqualTo(true);
    assertThatThrownBy(() -> svc.verifyGstin("29ABCDE1234F1ZW", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GSTN_API_UNAVAILABLE");
  }

  @Test
  void mockDrugFoundSyncWithoutExpiry() {
    DrugRegistryClientPort drug = mock(DrugRegistryClientPort.class);
    when(drug.verify(anyString(), anyString(), anyString()))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                false,
                false,
                true,
                true,
                "H",
                LocalDate.of(2020, 1, 1),
                null,
                List.of("OTC"),
                "Karnataka",
                "RETAIL",
                "ACTIVE"));
    GovernmentApiService svc =
        service(new StubGstnClient(), drug, new StubFssaiClient(CLOCK), new StubDigiLockerClient());
    Map<String, Object> data = svc.verifyDrugLicence("X", "Karnataka", "RETAIL", null, null);
    assertThat(data.get("is_expired")).isEqualTo(false);
    assertThat(data.get("expiry_date")).isNull();
  }

  @Test
  void fssaiExpiredStatus() {
    FssaiClientPort fssai = mock(FssaiClientPort.class);
    when(fssai.verify(anyString()))
        .thenReturn(
            Optional.of(
                new FssaiClientPort.FssaiResult(
                    true, true, false, "Biz", "STATE", LocalDate.of(2020, 1, 1), "ACTIVE")));
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            fssai,
            new StubDigiLockerClient());
    Map<String, Object> data = svc.verifyFssai("1001", null, null);
    assertThat(data.get("is_expired")).isEqualTo(true);
    assertThat(data.get("status")).isEqualTo("EXPIRED");
  }

  @Test
  void secondsUntilTokenWhenBucketHasCapacity() {
    GovernmentApiService svc =
        service(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient());
    svc.verifyGstin("29ABCDE1234F1ZW", null, null);
    svc.drainGstnTokens();
    assertThatThrownBy(() -> svc.verifyGstin("27AAPFU0939F1ZV", null, null))
        .isInstanceOf(AppException.class);
  }
}
