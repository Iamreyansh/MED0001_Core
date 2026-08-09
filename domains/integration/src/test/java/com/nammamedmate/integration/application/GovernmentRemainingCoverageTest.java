package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.adapter.out.client.StubDigiLockerClient;
import com.nammamedmate.integration.adapter.out.client.StubDrugRegistryClient;
import com.nammamedmate.integration.adapter.out.client.StubFssaiClient;
import com.nammamedmate.integration.adapter.out.client.StubGstnClient;
import com.nammamedmate.integration.adapter.out.persistence.JdbcGovernmentVerificationCacheStore;
import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import com.nammamedmate.integration.application.port.out.DrugRegistryClientPort;
import com.nammamedmate.integration.application.port.out.FssaiClientPort;
import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.integration.domain.GovernmentVerificationCacheEntry;
import com.nammamedmate.integration.domain.GovernmentVerificationTypes;
import com.nammamedmate.integration.domain.GstinChecksum;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GovernmentRemainingCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private GovernmentApiService svc(
      GstnClientPort gstn,
      DrugRegistryClientPort drug,
      FssaiClientPort fssai,
      DigiLockerClientPort digi,
      Clock clock) {
    return new GovernmentApiService(
        gstn,
        drug,
        fssai,
        digi,
        new InMemoryStores.GovCache(),
        new InMemoryStores.GovLogs(),
        (t, a, i, p) -> {},
        clock);
  }

  @Test
  void nullAndBlankInputs() {
    GovernmentApiService service =
        svc(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient("cid", "https://auth"),
            CLOCK);
    assertThatThrownBy(() -> service.verifyGstin(null, null, null))
        .isInstanceOf(AppException.class);
    assertThat(service.verifyFssai(null, null, null).get("valid")).isEqualTo(true);
    assertThatThrownBy(() -> service.verifyFssai("NOTFOUND-1", null, null))
        .isInstanceOf(AppException.class);
    assertThat(service.verifyDrugLicence(null, null, "  ", null, null).get("status"))
        .isEqualTo("PENDING");
    assertThatThrownBy(() -> service.initiateDigiLocker("12", "P", UUID.randomUUID(), null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.digiLockerCallback("c", null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> GstinChecksum.checkDigit(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void digiLockerExpiredStateAndNullDob() {
    DigiLockerClientPort digi = mock(DigiLockerClientPort.class);
    when(digi.buildAuthorizeUrl(any(), any()))
        .thenReturn(new DigiLockerClientPort.AuthUrl("https://a", "st", 600));
    when(digi.exchangeCode(any()))
        .thenReturn(
            new DigiLockerClientPort.DigiLockerDocuments(
                true, "Name", null, "addr", List.of("AADHAAR")));
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    Clock mutable =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    GovernmentApiService service =
        svc(
            new StubGstnClient(),
            new StubDrugRegistryClient(mutable),
            new StubFssaiClient(mutable),
            digi,
            mutable);
    UUID entity = UUID.randomUUID();
    Map<String, Object> init = service.initiateDigiLocker(null, null, entity, "https://cb");
    now.set(NOW.plusSeconds(700));
    assertThatThrownBy(() -> service.digiLockerCallback("c", init.get("state").toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATE");

    now.set(NOW);
    Map<String, Object> again = service.initiateDigiLocker("12", null, entity, "https://cb");
    Map<String, Object> cb = service.digiLockerCallback("code", again.get("state").toString());
    assertThat(cb.get("dob")).isNull();
  }

  @Test
  void forceSyncFoundPathAndExpiringPoll() {
    DrugRegistryClientPort drug = mock(DrugRegistryClientPort.class);
    when(drug.verify(anyString(), anyString(), anyString()))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                true, false, false, false, null, null, null, List.of(), "Odisha", "RETAIL",
                "PENDING"))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                false,
                false,
                true,
                true,
                "H",
                null,
                LocalDate.of(2030, 1, 1),
                List.of("OTC"),
                "Odisha",
                "RETAIL",
                "ACTIVE"));
    GovernmentApiService service =
        svc(
            new StubGstnClient(),
            drug,
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient(),
            CLOCK);
    Map<String, Object> pending = service.verifyDrugLicence("OD/1", "Odisha", "RETAIL", null, null);
    UUID id = UUID.fromString(pending.get("verification_id").toString());
    Map<String, Object> done = service.getDrugVerification(id);
    assertThat(done.get("status")).isEqualTo("ACTIVE");
    assertThat(done.get("issued_date")).isNull();

    GovernmentApiService stub =
        svc(
            new StubGstnClient(),
            new StubDrugRegistryClient(CLOCK),
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient(),
            CLOCK);
    Map<String, Object> expiring =
        stub.verifyDrugLicence("OD/EXPIRING/1", "Odisha", "RETAIL", "PHARMACY", null);
    Map<String, Object> polled =
        stub.getDrugVerification(UUID.fromString(expiring.get("verification_id").toString()));
    assertThat(polled.get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void gstnNullRegisteredAtAndFssaiNullExpiry() {
    GstnClientPort gstn = mock(GstnClientPort.class);
    when(gstn.verify(anyString()))
        .thenReturn(
            Optional.of(
                new GstnClientPort.GstnResult(
                    true, true, "T", "L", "ACTIVE", "REGULAR", "KA", "29", null)));
    FssaiClientPort fssai = mock(FssaiClientPort.class);
    when(fssai.verify(anyString()))
        .thenReturn(
            Optional.of(
                new FssaiClientPort.FssaiResult(true, true, false, "B", "C", null, "ACTIVE")));
    GovernmentApiService service =
        svc(gstn, new StubDrugRegistryClient(CLOCK), fssai, new StubDigiLockerClient(), CLOCK);
    assertThat(service.verifyGstin("29ABCDE1234F1ZW", null, null).get("registered_at")).isNull();
    assertThat(service.verifyFssai("1001", null, null).get("expiry_date")).isNull();
  }

  @Test
  void drugExpiredByDateNotStatus() {
    DrugRegistryClientPort drug = mock(DrugRegistryClientPort.class);
    when(drug.verify(anyString(), anyString(), anyString()))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                false,
                false,
                true,
                true,
                "H",
                LocalDate.of(2019, 1, 1),
                LocalDate.of(2020, 1, 1),
                List.of(),
                "KA",
                "RETAIL",
                "ACTIVE"));
    GovernmentApiService service =
        svc(
            new StubGstnClient(),
            drug,
            new StubFssaiClient(CLOCK),
            new StubDigiLockerClient(),
            CLOCK);
    Map<String, Object> data = service.verifyDrugLicence("KA/1", "KA", "RETAIL", null, null);
    assertThat(data.get("is_expired")).isEqualTo(true);
    assertThat(data.get("status")).isEqualTo("EXPIRED");
  }

  @Test
  void jdbcJsonErrorPaths() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any())).thenThrow(new RuntimeException("ser"));
    JdbcGovernmentVerificationCacheStore store =
        new JdbcGovernmentVerificationCacheStore(jdbc, bad);
    GovernmentVerificationCacheEntry entry =
        new GovernmentVerificationCacheEntry(
            Ids.newId(),
            GovernmentVerificationTypes.GSTIN,
            "x",
            null,
            Map.of("a", 1),
            true,
            null,
            NOW,
            NOW.plusSeconds(1));
    assertThatThrownBy(() -> store.upsert(entry)).isInstanceOf(IllegalStateException.class);

    ObjectMapper good = new ObjectMapper();
    JdbcGovernmentVerificationCacheStore store2 =
        new JdbcGovernmentVerificationCacheStore(jdbc, good);
    store2.upsert(
        new GovernmentVerificationCacheEntry(
            Ids.newId(),
            GovernmentVerificationTypes.FSSAI,
            "y",
            "",
            Map.of(),
            false,
            null,
            NOW,
            NOW.plusSeconds(1)));
  }

  @Test
  void stubClientNullInputsAndStateNames() {
    assertThat(new StubGstnClient().verify(null)).isPresent();
    assertThat(new StubGstnClient().verify("33ABCDE1234F1Z0").get().state())
        .isEqualTo("Tamil Nadu");
    assertThat(new StubGstnClient().verify("27ABCDE1234F1Z0").get().state())
        .isEqualTo("Maharashtra");
    assertThat(new StubDrugRegistryClient(CLOCK).verify(null, null, null).async()).isTrue();
    assertThat(new StubFssaiClient(CLOCK).verify(null)).isPresent();
    assertThat(new StubDigiLockerClient(null, null).buildAuthorizeUrl(null, "s").authUrl())
        .contains("client_id=NM_CLIENT");
    assertThat(
            new StubDigiLockerClient("cid", "https://custom/authorize")
                .buildAuthorizeUrl("https://cb", "s")
                .authUrl())
        .contains("https://custom/authorize");
    assertThatThrownBy(() -> new StubDigiLockerClient().exchangeCode(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new StubDrugRegistryClient(CLOCK).verify("KA/1", "KA", "").licenceType())
        .isEqualTo("RETAIL");
  }

  @Test
  void fssaiInvalidAndForceSyncNotFound() {
    FssaiClientPort fssai = mock(FssaiClientPort.class);
    when(fssai.verify(anyString()))
        .thenReturn(
            Optional.of(
                new FssaiClientPort.FssaiResult(
                    true, false, false, "B", "C", LocalDate.of(2030, 1, 31), "SUSPENDED")));
    DrugRegistryClientPort drug = mock(DrugRegistryClientPort.class);
    when(drug.verify(anyString(), anyString(), anyString()))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                true, false, false, false, null, null, null, List.of(), "Odisha", "RETAIL",
                "PENDING"))
        .thenReturn(
            new DrugRegistryClientPort.DrugLicenceResult(
                false, false, false, false, null, null, null, List.of(), "Odisha", "RETAIL",
                "ACTIVE"));
    GovernmentApiService service =
        svc(new StubGstnClient(), drug, fssai, new StubDigiLockerClient(), CLOCK);
    assertThat(service.verifyFssai("1001", null, null).get("valid")).isEqualTo(false);
    Map<String, Object> pending =
        service.verifyDrugLicence("OD/OK/1", "Odisha", "RETAIL", null, null);
    Map<String, Object> done =
        service.getDrugVerification(UUID.fromString(pending.get("verification_id").toString()));
    assertThat(done.get("expiry_date")).isNotNull();
    service.initiateDigiLocker("+919876543210", "P", UUID.randomUUID(), "https://cb");
  }
}
