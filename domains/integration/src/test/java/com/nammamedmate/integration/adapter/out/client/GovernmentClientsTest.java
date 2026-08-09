package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.DigiLockerClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GovernmentClientsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void stubGstnPaths() {
    StubGstnClient stub = new StubGstnClient();
    assertThat(stub.verify("27AAPFU0939F1ZV")).isPresent();
    assertThat(stub.verify("29AAAAA0000A1ZY")).isEmpty();
    assertThatThrownBy(() -> stub.verify("29AAAAA9999A1ZG")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> new StubGstnClient(true).verify("27AAPFU0939F1ZV"))
        .isInstanceOf(AppException.class);
    assertThat(stub.verify("07ABCDE1234F1Z0").get().state()).isEqualTo("Delhi");
  }

  @Test
  void stubDrugAndFssaiAndDigi() {
    StubDrugRegistryClient drug = new StubDrugRegistryClient(CLOCK);
    assertThat(drug.verify("KA/1", "Karnataka", null).async()).isFalse();
    assertThat(drug.verify("X", "Odisha", "RETAIL").async()).isTrue();
    assertThat(drug.verify("DOWN-1", "KA", "RETAIL").manualReviewRequired()).isTrue();

    StubFssaiClient fssai = new StubFssaiClient(CLOCK);
    assertThat(fssai.verify("1001")).isPresent();
    assertThat(fssai.verify("UNKNOWN")).isEmpty();
    assertThat(fssai.verify("DOWN").get().manualReviewRequired()).isTrue();

    StubDigiLockerClient digi = new StubDigiLockerClient("", "");
    DigiLockerClientPort.AuthUrl auth = digi.buildAuthorizeUrl("https://cb", "st");
    assertThat(auth.authUrl()).contains("client_id=");
    assertThat(digi.exchangeCode("c").aadhaarVerified()).isTrue();
    assertThatThrownBy(() -> digi.exchangeCode(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void liveGstnClient() {
    LiveGstnClient found =
        new LiveGstnClient(
            "key",
            "https://gstn.example/",
            mapper,
            uri ->
                """
                {"found":true,"valid":true,"trade_name":"T","legal_name":"L",
                 "registration_status":"ACTIVE","filing_status":"REGULAR",
                 "state":"KA","state_code":"29","registered_at":"2018-04-01"}
                """);
    assertThat(found.verify("29ABCDE1234F1ZW")).isPresent();

    LiveGstnClient sparse =
        new LiveGstnClient("key", "https://gstn.example", mapper, uri -> "{\"trade_name\":null}");
    assertThat(sparse.verify("x")).isPresent();
    assertThat(sparse.verify("x").get().registeredAt()).isNull();

    LiveGstnClient blankDate =
        new LiveGstnClient(
            "key",
            "https://gstn.example",
            mapper,
            uri -> "{\"trade_name\":\"T\",\"registered_at\":\"\"}");
    assertThat(blankDate.verify("x").get().registeredAt()).isNull();

    LiveGstnClient missing = new LiveGstnClient("key", "https://gstn.example", mapper, uri -> "{}");
    assertThat(missing.verify("x")).isEmpty();

    LiveGstnClient boom =
        new LiveGstnClient(
            "key",
            "https://gstn.example",
            mapper,
            uri -> {
              throw new RuntimeException("x");
            });
    assertThatThrownBy(() -> boom.verify("x")).isInstanceOf(AppException.class);

    LiveGstnClient app =
        new LiveGstnClient(
            "key",
            "https://gstn.example",
            mapper,
            uri -> {
              throw new AppException("GSTN_API_UNAVAILABLE", "d", 503);
            });
    assertThatThrownBy(() -> app.verify("x")).isInstanceOf(AppException.class);
  }

  @Test
  void liveDrugClientBranches() {
    LiveDrugRegistryClient async =
        new LiveDrugRegistryClient(
            "k", "https://d.example/", mapper, uri -> "{\"async\":true,\"status\":\"PENDING\"}");
    assertThat(async.verify("L", "KA", "RETAIL").async()).isTrue();

    LiveDrugRegistryClient pendingStatus =
        new LiveDrugRegistryClient(
            "k", "https://d.example", mapper, uri -> "{\"status\":\"PENDING\"}");
    assertThat(pendingStatus.verify("L", "KA", "RETAIL").async()).isTrue();

    LiveDrugRegistryClient manual =
        new LiveDrugRegistryClient(
            "k", "https://d.example", mapper, uri -> "{\"manual_review\":true}");
    assertThat(manual.verify("L", "KA", "RETAIL").manualReviewRequired()).isTrue();

    LiveDrugRegistryClient ok =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri ->
                """
                {"found":true,"valid":true,"holder_name":"H","issued_date":"2019-06-15",
                 "expiry_date":"2030-06-14","drugs_permitted":["OTC"],"status":"ACTIVE",
                 "licence_type":"RETAIL"}
                """);
    assertThat(ok.verify("L", "KA", null).valid()).isTrue();

    LiveDrugRegistryClient sparse =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri ->
                """
                {"found":true,"valid":false,"holder_name":null,
                 "drugs_permitted":[],"status":"ACTIVE"}
                """);
    assertThat(sparse.verify(null, null, "RETAIL").valid()).isFalse();

    LiveDrugRegistryClient blankDates =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri ->
                """
                {"found":true,"valid":true,"issued_date":"","expiry_date":"",
                 "drugs_permitted":[],"status":"ACTIVE"}
                """);
    assertThat(blankDates.verify("L", "KA", "RETAIL").expiryDate()).isNull();

    LiveDrugRegistryClient expired =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri ->
                """
                {"found":true,"valid":true,"holder_name":"H","issued_date":"2019-06-15",
                 "expiry_date":"2020-01-01","drugs_permitted":[],"status":"ACTIVE"}
                """);
    assertThat(expired.verify("L", "KA", "RETAIL").status()).isEqualTo("EXPIRED");

    LiveDrugRegistryClient boom =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri -> {
              throw new RuntimeException("x");
            });
    assertThat(boom.verify("L", "KA", "RETAIL").manualReviewRequired()).isTrue();

    LiveDrugRegistryClient appEx =
        new LiveDrugRegistryClient(
            "k",
            "https://d.example",
            mapper,
            uri -> {
              throw new AppException("X", "y", 503);
            });
    assertThatThrownBy(() -> appEx.verify("L", "KA", "RETAIL")).isInstanceOf(AppException.class);
  }

  @Test
  void liveFssaiAndDigiLocker() {
    LiveFssaiClient ok =
        new LiveFssaiClient(
            "k",
            "https://f.example/",
            mapper,
            uri ->
                """
                {"found":true,"business_name":"B","category":"C","expiry_date":"2030-01-31",
                 "status":"ACTIVE","valid":true}
                """);
    assertThat(ok.verify("1")).isPresent();

    LiveFssaiClient sparse =
        new LiveFssaiClient(
            "k", "https://f.example", mapper, uri -> "{\"found\":true,\"business_name\":null}");
    assertThat(sparse.verify("1").get().expiryDate()).isNull();

    LiveFssaiClient blankDate =
        new LiveFssaiClient(
            "k",
            "https://f.example",
            mapper,
            uri -> "{\"found\":true,\"business_name\":\"B\",\"expiry_date\":\"\"}");
    assertThat(blankDate.verify("1").get().expiryDate()).isNull();

    LiveFssaiClient missing =
        new LiveFssaiClient("k", "https://f.example", mapper, uri -> "{\"found\":false}");
    assertThat(missing.verify("1")).isEmpty();

    LiveFssaiClient manual =
        new LiveFssaiClient("k", "https://f.example", mapper, uri -> "{\"manual_review\":true}");
    assertThat(manual.verify("1").get().manualReviewRequired()).isTrue();

    LiveFssaiClient expired =
        new LiveFssaiClient(
            "k",
            "https://f.example",
            mapper,
            uri ->
                """
                {"found":true,"business_name":"B","category":"C","expiry_date":"2020-01-01",
                 "status":"ACTIVE"}
                """);
    assertThat(expired.verify("1").get().status()).isEqualTo("EXPIRED");

    LiveFssaiClient boom =
        new LiveFssaiClient(
            "k",
            "https://f.example",
            mapper,
            uri -> {
              throw new RuntimeException("x");
            });
    assertThat(boom.verify("1").get().manualReviewRequired()).isTrue();

    LiveFssaiClient appEx =
        new LiveFssaiClient(
            "k",
            "https://f.example",
            mapper,
            uri -> {
              throw new AppException("X", "y", 503);
            });
    assertThatThrownBy(() -> appEx.verify("1")).isInstanceOf(AppException.class);

    AtomicReference<LiveDigiLockerClient.TokenRequest> seen = new AtomicReference<>();
    LiveDigiLockerClient digi =
        new LiveDigiLockerClient(
            "cid",
            "sec",
            "https://auth",
            "https://token",
            mapper,
            req -> {
              seen.set(req);
              return """
              {"aadhaar_verified":true,"name_on_aadhaar":"N","dob":"1985-03-15",
               "address":"A","documents_fetched":["AADHAAR"]}
              """;
            });
    assertThat(digi.buildAuthorizeUrl(null, "st").authUrl()).contains("cid");
    assertThat(digi.exchangeCode("code").nameOnAadhaar()).isEqualTo("N");
    assertThat(seen.get().body()).contains("client_secret=");

    LiveDigiLockerClient digiSparse =
        new LiveDigiLockerClient(
            "c",
            "s",
            "https://a",
            "https://t",
            mapper,
            req ->
                "{\"aadhaar_verified\":true,\"name_on_aadhaar\":null,\"dob\":\"\",\"address\":null}");
    assertThat(digiSparse.exchangeCode("c").dob()).isNull();

    LiveDigiLockerClient emptyDocs =
        new LiveDigiLockerClient(
            "c", "s", "https://a", "https://t", mapper, req -> "{\"aadhaar_verified\":true}");
    assertThat(emptyDocs.exchangeCode("c").documentsFetched()).contains("AADHAAR");

    LiveDigiLockerClient fail =
        new LiveDigiLockerClient(
            "c",
            "s",
            "https://a",
            "https://t",
            mapper,
            req -> {
              throw new RuntimeException("x");
            });
    assertThatThrownBy(() -> fail.exchangeCode("c")).isInstanceOf(AppException.class);

    LiveDigiLockerClient app =
        new LiveDigiLockerClient(
            "c",
            "s",
            "https://a",
            "https://t",
            mapper,
            req -> {
              throw new AppException("DIGILOCKER_UNAVAILABLE", "x", 503);
            });
    assertThatThrownBy(() -> app.exchangeCode("c")).isInstanceOf(AppException.class);
  }
}
