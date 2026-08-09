package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.EinvoiceServiceAcTest;
import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GspClientTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);

  @Test
  void stubGenerateCancelStatusAndToken() {
    StubGspClient stub = new StubGspClient(CLOCK);
    GspClientPort.IrnResult result = stub.generateIrn(EinvoiceServiceAcTest.validInvoice());
    assertThat(result.irn()).hasSize(64);
    assertThat(result.signedInvoiceJson()).contains("Signature");
    stub.cancelIrn(result.irn(), "1", "dup");
    assertThat(stub.getStatus(result.irn()).status()).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> stub.cancelIrn(result.irn(), "1", "again"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_ALREADY_CANCELLED");
    assertThat(stub.refreshToken().accessToken()).startsWith("stub-gsp-token");
    assertThat(stub.currentToken()).isPresent();
  }

  @Test
  void stubErrorPaths() {
    StubGspClient down = new StubGspClient(CLOCK, true);
    assertThatThrownBy(() -> down.generateIrn(EinvoiceServiceAcTest.validInvoice()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThatThrownBy(() -> down.cancelIrn("x", "1", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThatThrownBy(() -> down.getStatus("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThatThrownBy(down::refreshToken)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");

    StubGspClient stub = new StubGspClient(CLOCK);
    assertThatThrownBy(() -> stub.getStatus("dead" + "0".repeat(60)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    assertThatThrownBy(() -> stub.cancelIrn("dead" + "1".repeat(60), "1", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    // cancel unknown non-dead IRN registers then cancels
    stub.cancelIrn("a".repeat(64), "4", "others");
    assertThat(stub.getStatus("a".repeat(64)).status()).isEqualTo("CANCELLED");
  }

  @Test
  void liveClientHappyPaths() {
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> lastPath = new AtomicReference<>();
    LiveGspClient live =
        new LiveGspClient(
            "cid",
            "sec",
            "https://gsp.test/v1",
            mapper,
            req -> {
              lastPath.set(req.uri().getPath());
              if (req.uri().getPath().endsWith("/auth/token")) {
                return "{\"access_token\":\"tok\",\"expires_in\":3600}";
              }
              if (req.uri().getPath().endsWith("/einvoice/generate")) {
                return """
                    {"irn":"abcd","ack_number":"1","ack_date":"2026-07-24T10:00:00Z",
                     "qr_code_url":"data:image/png;base64,xx","signed_invoice_json":"{\\"Signature\\":\\"x\\"}"}
                    """;
              }
              if (req.uri().getPath().endsWith("/einvoice/cancel")) {
                return "{\"status\":\"CANCELLED\"}";
              }
              if (req.uri().getPath().contains("/einvoice/status/")) {
                return """
                    {"irn":"abcd","status":"ACTIVE","ack_number":"1",
                     "ack_date":"2026-07-24T10:00:00Z","cancelled_at":null}
                    """;
              }
              return "{}";
            });
    assertThat(live.refreshToken().accessToken()).isEqualTo("tok");
    assertThat(live.generateIrn(EinvoiceServiceAcTest.validInvoice()).irn()).isEqualTo("abcd");
    live.cancelIrn("abcd", "1", "dup");
    assertThat(live.getStatus("abcd").status()).isEqualTo("ACTIVE");
    assertThat(lastPath.get()).contains("/einvoice/status/");
    assertThat(live.currentToken()).isPresent();
  }

  @Test
  void liveClientErrorCodes() {
    ObjectMapper mapper = new ObjectMapper();
    LiveGspClient live =
        new LiveGspClient(
            "cid",
            "sec",
            "https://gsp.test/v1/",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                return "{\"access_token\":\"tok\",\"expires_in\":3600}";
              }
              if (req.uri().getPath().endsWith("/einvoice/generate")) {
                String body = req.body() == null ? "" : req.body();
                if (body.contains("DUPLICATE")) {
                  return "{\"error_code\":\"DUPLICATE_IRN\",\"irn\":\"existing\"}";
                }
                return "{\"error_code\":\"SELLER_GSTIN_NOT_REGISTERED\"}";
              }
              if (req.uri().getPath().endsWith("/einvoice/cancel")) {
                return "{\"error_code\":\"IRN_ALREADY_CANCELLED\"}";
              }
              return "{\"error_code\":\"IRN_NOT_FOUND\"}";
            });
    assertThatThrownBy(() -> live.generateIrn(EinvoiceServiceAcTest.validInvoice()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SELLER_GSTIN_NOT_REGISTERED");
    Map<String, Object> dup = EinvoiceServiceAcTest.validInvoice();
    dup.put("invoice_number", "DUPLICATE");
    assertThatThrownBy(() -> live.generateIrn(dup))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_IRN");
    assertThatThrownBy(() -> live.cancelIrn("x", "1", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_ALREADY_CANCELLED");
    assertThatThrownBy(() -> live.getStatus("missing"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
  }

  @Test
  void liveClientTransportFailures() {
    ObjectMapper mapper = new ObjectMapper();
    LiveGspClient live =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              throw new RuntimeException("boom");
            });
    assertThatThrownBy(live::refreshToken)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    // ensureToken → refresh fails
    assertThatThrownBy(() -> live.generateIrn(Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
  }

  @Test
  void liveCancelNotFoundAndParseEdges() {
    ObjectMapper mapper = new ObjectMapper();
    LiveGspClient live =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                return "{\"access_token\":\"t\",\"expires_in\":86400}";
              }
              if (req.uri().getPath().endsWith("/einvoice/cancel")) {
                return "{\"error_code\":\"IRN_NOT_FOUND\"}";
              }
              if (req.uri().getPath().contains("/status/")) {
                return """
                    {"irn":"x","status":"CANCELLED","ack_number":"1",
                     "ack_date":"","cancelled_at":"2026-07-24T11:00:00Z"}
                    """;
              }
              return "{\"irn\":\"x\",\"ack_number\":\"1\",\"ack_date\":null,\"qr_code_url\":null,\"signed_invoice_json\":null}";
            });
    assertThatThrownBy(() -> live.cancelIrn("x", "1", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    assertThat(live.getStatus("x").cancelledAt()).isNotNull();
    assertThat(live.generateIrn(EinvoiceServiceAcTest.validInvoice()).ackDate()).isNotNull();
    // token still valid → ensureToken skips refresh; blank cancelled_at
    LiveGspClient live2 =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                return "{\"access_token\":\"t2\",\"expires_in\":86400}";
              }
              return """
                  {"irn":"y","status":"ACTIVE","ack_number":"1",
                   "ack_date":"2026-07-24T10:00:00Z","cancelled_at":""}
                  """;
            });
    live2.refreshToken();
    assertThat(live2.getStatus("y").cancelledAt()).isNull();
  }

  @Test
  void liveRethrowsAppExceptionAndPostTokenFailures() {
    ObjectMapper mapper = new ObjectMapper();
    LiveGspClient live =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                throw new AppException("NIC_PORTAL_UNAVAILABLE", "auth down", 503);
              }
              throw new RuntimeException("unreachable");
            });
    assertThatThrownBy(live::refreshToken)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");

    AtomicReference<Integer> calls = new AtomicReference<>(0);
    LiveGspClient afterToken =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                return "{\"access_token\":\"t\",\"expires_in\":86400}";
              }
              calls.set(calls.get() + 1);
              throw new RuntimeException("nic down");
            });
    afterToken.refreshToken();
    assertThatThrownBy(() -> afterToken.generateIrn(EinvoiceServiceAcTest.validInvoice()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThatThrownBy(() -> afterToken.cancelIrn("x", "1", "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThatThrownBy(() -> afterToken.getStatus("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void stubMissingStatusAndNullIrnBranches() {
    StubGspClient stub = new StubGspClient(CLOCK);
    assertThatThrownBy(() -> stub.getStatus("missing" + "0".repeat(57)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    stub.cancelIrn(null, "1", "x");
    assertThat(stub.getStatus(null).status()).isEqualTo("CANCELLED");
    Map<String, Object> partial = new java.util.HashMap<>();
    partial.put("seller_gstin", "29ABCDE1234F1ZW");
    partial.put("buyer_gstin", null);
    stub.generateIrn(partial);
    assertThatThrownBy(() -> StubGspClient.digestHex("x", "NOT_A_REAL_ALG"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void liveEnsureTokenExpiredRefreshesAndTextNullField() {
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> tokenBody =
        new AtomicReference<>("{\"access_token\":\"t1\",\"expires_in\":0}");
    LiveGspClient live =
        new LiveGspClient(
            "c",
            "s",
            "https://gsp.test/v1",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/auth/token")) {
                return tokenBody.get();
              }
              // omit ack_number (missing node) + JSON null cancelled_at
              return "{\"irn\":\"z\",\"status\":\"ACTIVE\",\"ack_date\":\"2026-07-24T10:00:00Z\",\"cancelled_at\":null}";
            });
    live.refreshToken(); // expires_in=0 → immediately stale
    tokenBody.set("{\"access_token\":\"t2\",\"expires_in\":86400}");
    assertThat(live.getStatus("z").ackNumber()).isNull();
    assertThat(live.currentToken().orElseThrow().accessToken()).isEqualTo("t2");
  }
}
