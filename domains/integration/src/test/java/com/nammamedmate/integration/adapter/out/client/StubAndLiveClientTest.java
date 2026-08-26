package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.CashfreeClientPort;
import com.nammamedmate.integration.application.port.out.CashfreePayoutClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StubAndLiveClientTest {

  @Test
  void stubWebhookAndUpi() {
    StubCashfreeClient client = new StubCashfreeClient();
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String sig = CashfreeHmac.hmacHex(StubCashfreeClient.DEFAULT_WEBHOOK_SECRET, "{}");
    assertThat(client.verifyWebhookSignature(sig, body)).isTrue();
    assertThat(client.verifyWebhookSignature("x", body)).isFalse();
    assertThat(client.verifyWebhookSignature(null, body)).isFalse();
    assertThat(client.verifyUpi("user@okaxis").valid()).isTrue();
    assertThat(client.verifyUpi("user@okicici").name()).contains("USER");
    assertThat(client.mode()).isEqualTo("TEST");
    CashfreeClientPort.CreateOrderResult order = client.createOrder(500, "INR", "r1", Map.of());
    assertThat(order.gatewayOrderId()).startsWith("order_stub_");
    assertThat(client.createOrder(500, null, "r1", Map.of()).currency()).isEqualTo("INR");
    assertThat(client.capturePayment("pay_1", 500).status()).isEqualTo("captured");
  }

  @Test
  void stubFailPaths() {
    StubCashfreeClient fail = new StubCashfreeClient("sec", true);
    assertThatThrownBy(() -> fail.createOrder(100, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    StubCashfreePayoutClient xFail = new StubCashfreePayoutClient(true, true, true);
    assertThatThrownBy(
            () ->
                xFail.createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "123456789", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                xFail.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 100, "IMPS", "payout", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void liveCashfreeClientHappyAndErrors() {
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> lastUrl = new AtomicReference<>();
    LiveCashfreeClient client =
        new LiveCashfreeClient(
            "key",
            "secret",
            "wh",
            "LIVE",
            mapper,
            req -> {
              lastUrl.set(req.uri().toString());
              if (req.uri().getPath().endsWith("/orders")) {
                return "{\"id\":\"order_live\",\"status\":\"created\"}";
              }
              if (req.uri().getPath().contains("/capture")) {
                return "{\"status\":\"captured\"}";
              }
              if (req.uri().getPath().contains("validate")) {
                return "{\"success\":true,\"customer_name\":\"TEST USER\"}";
              }
              return "{}";
            });
    assertThat(client.mode()).isEqualTo("LIVE");
    assertThat(client.createOrder(100, "INR", "r", Map.of("a", "b")).gatewayOrderId())
        .isEqualTo("order_live");
    assertThat(client.capturePayment("pay_1", 100).status()).isEqualTo("captured");
    assertThat(client.verifyUpi("a@okicici").name()).isEqualTo("TEST USER");
    String sig = CashfreeHmac.hmacHex("wh", "{}");
    assertThat(client.verifyWebhookSignature(sig, "{}".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(lastUrl.get()).contains("api.cashfree.com/pg");

    LiveCashfreeClient bad =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req -> {
              throw new RuntimeException("down");
            });
    assertThatThrownBy(() -> bad.createOrder(100, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
  }

  @Test
  void liveCashfreePayoutClientHappyAndBalanceError() {
    ObjectMapper mapper = new ObjectMapper();
    LiveCashfreePayoutClient client =
        new LiveCashfreePayoutClient(
            "xk",
            "xs",
            mapper,
            req -> {
              if (req.uri().getPath().contains("/addBeneficiary")
                  || req.uri().getPath().endsWith("/contacts")) {
                return "{\"id\":\"cont_1\",\"beneId\":\"cont_1\"}";
              }
              if (req.uri().getPath().contains("/requestTransfer")
                  || req.uri().getPath().endsWith("/payouts")
                  || req.uri().getPath().endsWith("/fund_accounts")) {
                if (req.uri().getPath().endsWith("/fund_accounts")) {
                  return "{\"id\":\"fa_1\"}";
                }
                return "{\"id\":\"pout_1\",\"transferId\":\"pout_1\",\"status\":\"processing\"}";
              }
              return "{}";
            });
    CashfreePayoutClientPort.BeneficiaryResult fa =
        client.createBeneficiary(
            new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                "PHARMACY", "e1", "HDFC", "50100123456789", "HDFC0001234", "Name"));
    assertThat(fa.contactId()).isEqualTo("cont_1");
    assertThat(fa.beneficiaryId()).isEqualTo("cont_1");
    assertThat(
            client
                .createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "cont_1", 1000, "IMPS", "payout", "ref", Map.of("k", "v")))
                .payoutId())
        .isEqualTo("pout_1");

    LiveCashfreePayoutClient bal =
        new LiveCashfreePayoutClient(
            "xk",
            "xs",
            mapper,
            req ->
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"insufficient balance\"}}");
    assertThatThrownBy(
            () ->
                bal.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "payout", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void hmacThrowsOnBogusAlgorithm() {
    assertThat(CashfreeHmac.hmacHex("s", "p")).hasSize(64);
    assertThatThrownBy(() -> CashfreeHmac.hmacHex("s", "p", "nope"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void hmacVerifyBranchesAndDefaultPort() throws Exception {
    assertThat(CashfreeHmac.signedPayload(null, null)).isEmpty();
    assertThat(CashfreeHmac.signedPayload("ts", null)).isEqualTo("ts");
    assertThat(CashfreeHmac.signedPayload(null, "body")).isEqualTo("body");
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(CashfreeHmac.verify(null, "sig", null, body)).isFalse();
    assertThat(CashfreeHmac.verify(" ", "sig", null, body)).isFalse();
    assertThat(CashfreeHmac.verify("sec", null, null, body)).isFalse();
    assertThat(CashfreeHmac.verify("sec", "sig", null, null)).isFalse();
    String hex = CashfreeHmac.hmacHex("sec", CashfreeHmac.signedPayload("1", "{}"));
    assertThat(CashfreeHmac.verify("sec", hex, "1", body)).isTrue();
    javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
    m.init(
        new javax.crypto.spec.SecretKeySpec("sec".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expectedB64 =
        java.util.Base64.getEncoder()
            .encodeToString(m.doFinal("{}".getBytes(StandardCharsets.UTF_8)));
    assertThat(CashfreeHmac.verify("sec", expectedB64, null, body)).isTrue();

    CashfreeClientPort onlyTwoArg =
        new CashfreeClientPort() {
          @Override
          public CreateOrderResult createOrder(
              long amountPaise, String currency, String receipt, Map<String, String> notes) {
            return new CreateOrderResult("o", amountPaise, "INR", receipt, "created");
          }

          @Override
          public CaptureResult capturePayment(String gatewayPaymentId, long amountPaise) {
            return new CaptureResult(gatewayPaymentId, "captured");
          }

          @Override
          public UpiVerifyResult verifyUpi(String vpa) {
            return new UpiVerifyResult(vpa, true, "n");
          }

          @Override
          public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
            return signatureHeader != null && rawBody != null;
          }

          @Override
          public String mode() {
            return "TEST";
          }
        };
    assertThat(onlyTwoArg.verifyWebhookSignature("sig", "ts", body)).isTrue();
    assertThat(onlyTwoArg.verifyWebhookSignature(null, "ts", body)).isFalse();
  }

  @Test
  void stubConstructorsAndCaptureValidation() {
    StubCashfreeClient nullSecret = new StubCashfreeClient(null);
    assertThat(nullSecret.mode()).isEqualTo("TEST");
    StubCashfreeClient blankSecret = new StubCashfreeClient(" ");
    assertThat(blankSecret.verifyWebhookSignature(null, null)).isFalse();
    assertThat(blankSecret.verifyWebhookSignature("x", null)).isFalse();
    assertThat(blankSecret.verifyUpi("nope").valid()).isFalse();
    StubCashfreeClient fail = new StubCashfreeClient("sec", true);
    assertThatThrownBy(() -> fail.capturePayment("pay", 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    StubCashfreeClient ok = new StubCashfreeClient();
    assertThatThrownBy(() -> ok.capturePayment(" ", 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> ok.capturePayment(null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(
            ok.verifyUpi("x@invalidhandle1234567890123456789012345678901234567890123456789012345")
                .valid())
        .isFalse();
    new StubCashfreePayoutClient(true);
  }

  @Test
  void liveClientsErrorBranches() {
    ObjectMapper mapper = new ObjectMapper();
    LiveCashfreeClient missingId =
        new LiveCashfreeClient("k", "s", "w", null, mapper, req -> "{\"status\":\"created\"}");
    assertThat(missingId.mode()).isEqualTo("TEST");
    assertThatThrownBy(() -> missingId.createOrder(1, "INR", "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    LiveCashfreeClient badJson =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req -> {
              throw new AppException("CASHFREE_UNAVAILABLE", "x", 503);
            });
    assertThatThrownBy(() -> badJson.createOrder(1, "INR", "r", Map.of()))
        .isInstanceOf(AppException.class);
    LiveCashfreeClient unreadable =
        new LiveCashfreeClient("k", "s", "w", "TEST", mapper, req -> "not-json");
    assertThatThrownBy(() -> unreadable.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    LiveCashfreeClient vpaAlt =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req -> "{\"success\":true,\"account_holder_name\":\"ALT\"}");
    assertThat(vpaAlt.verifyUpi("a@b").name()).isEqualTo("ALT");
    assertThat(vpaAlt.verifyWebhookSignature(null, "{}".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
    assertThat(vpaAlt.verifyWebhookSignature("x", null)).isFalse();
    assertThat(LiveCashfreeClient.normalizeMode(null)).isEqualTo("TEST");
    assertThat(LiveCashfreeClient.normalizeMode(" ")).isEqualTo("TEST");
    assertThat(LiveCashfreeClient.normalizeMode("live")).isEqualTo("LIVE");
    new LiveCashfreeClient.Request(java.net.URI.create("https://example.com"), null, "{}");
    assertThat(LiveCashfreePayoutClient.messageMentionsBalance(null)).isFalse();
    assertThat(LiveCashfreePayoutClient.messageMentionsBalance("ok")).isFalse();
    assertThat(LiveCashfreePayoutClient.messageMentionsBalance("low balance")).isTrue();
    LiveCashfreePayoutClient codeBalance =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req ->
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"nope\"}}"
                    .replace("BAD_REQUEST_ERROR", "balance_error"));
    assertThatThrownBy(
            () ->
                codeBalance.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");

    LiveCashfreePayoutClient missingContact =
        new LiveCashfreePayoutClient("k", "s", mapper, req -> "{}");
    assertThatThrownBy(
            () ->
                missingContact.createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient missingFa =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> req.uri().getPath().endsWith("/contacts") ? "{\"id\":\"c1\"}" : "{}");
    assertThatThrownBy(
            () ->
                missingFa.createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient missingPayout =
        new LiveCashfreePayoutClient("k", "s", mapper, req -> "{}");
    assertThatThrownBy(
            () ->
                missingPayout.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient runtime =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> {
              throw new RuntimeException("down");
            });
    assertThatThrownBy(
            () ->
                runtime.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient appEx =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> {
              throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "balance low", 503);
            });
    assertThatThrownBy(
            () ->
                appEx.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
    LiveCashfreePayoutClient genericErr =
        new LiveCashfreePayoutClient(
            "k", "s", mapper, req -> "{\"error\":{\"code\":\"X\",\"description\":\"nope\"}}");
    assertThatThrownBy(
            () ->
                genericErr.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    new LiveCashfreePayoutClient.Request(java.net.URI.create("https://example.com"), null, "{}");

    ObjectMapper boom = Mockito.mock(ObjectMapper.class);
    try {
      Mockito.when(boom.writeValueAsString(Mockito.any()))
          .thenThrow(new JsonProcessingException("x") {});
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
    LiveCashfreeClient writeFail = new LiveCashfreeClient("k", "s", "w", "TEST", boom, req -> "{}");
    assertThatThrownBy(() -> writeFail.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    LiveCashfreePayoutClient writeFailX = new LiveCashfreePayoutClient("k", "s", boom, req -> "{}");
    assertThatThrownBy(
            () ->
                writeFailX.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient unreadableX =
        new LiveCashfreePayoutClient("k", "s", mapper, req -> "not-json");
    assertThatThrownBy(
            () ->
                unreadableX.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreeClient blankId =
        new LiveCashfreeClient(
            "k", "s", "w", "TEST", mapper, req -> "{\"id\":\"\",\"status\":\"x\"}");
    assertThatThrownBy(() -> blankId.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_UNAVAILABLE");
    LiveCashfreeClient vpaEmptyName =
        new LiveCashfreeClient(
            "k", "s", "w", "TEST", mapper, req -> "{\"success\":false,\"customer_name\":\"\"}");
    assertThat(vpaEmptyName.verifyUpi("a@b").valid()).isFalse();
    LiveCashfreePayoutClient blankContact =
        new LiveCashfreePayoutClient("k", "s", mapper, req -> "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankContact.createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient blankFa =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> req.uri().getPath().endsWith("/contacts") ? "{\"id\":\"c1\"}" : "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankFa.createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
    LiveCashfreePayoutClient blankPayout =
        new LiveCashfreePayoutClient("k", "s", mapper, req -> "{\"id\":\"\",\"status\":\"x\"}");
    assertThatThrownBy(
            () ->
                blankPayout.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
  }

  @Test
  void liveClientRemainingBranches() {
    ObjectMapper mapper = new ObjectMapper();
    LiveCashfreeClient genId =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req ->
                "{\"order_id\":\"order_auto\",\"payment_session_id\":\"sess\",\"order_status\":\"ACTIVE\"}");
    assertThat(genId.createOrder(100, null, null, Map.of()).gatewayOrderId())
        .isEqualTo("order_auto");
    assertThat(genId.createOrder(100, " ", " ", null).currency()).isEqualTo("INR");
    assertThatThrownBy(() -> genId.capturePayment(null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> genId.capturePayment("  ", 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    LiveCashfreeClient vpaValid =
        new LiveCashfreeClient(
            "k", "s", "w", "TEST", mapper, req -> "{\"valid\":true,\"name\":\"FROM_NAME\"}");
    assertThat(vpaValid.verifyUpi("a@b").name()).isEqualTo("FROM_NAME");
    LiveCashfreeClient vpaBlankName =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req -> "{\"valid\":true,\"name\":\"\",\"customer_name\":\"FROM_CUSTOMER\"}");
    assertThat(vpaBlankName.verifyUpi("a@b").name()).isEqualTo("FROM_CUSTOMER");
    LiveCashfreeClient vpaExists =
        new LiveCashfreeClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req ->
                "{\"account_exists\":true,\"customer_name\":\"\",\"account_holder_name\":\"H\"}");
    assertThat(vpaExists.verifyUpi("a@b").valid()).isTrue();
    assertThat(vpaExists.verifyUpi("a@b").name()).isEqualTo("H");

    LiveCashfreePayoutClient nestedBene =
        new LiveCashfreePayoutClient(
            "k", "s", mapper, req -> "{\"data\":{\"beneId\":\"bene_nested\"}}");
    assertThat(
            nestedBene
                .createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n"))
                .beneficiaryId())
        .isEqualTo("bene_nested");
    LiveCashfreePayoutClient blankThenNested =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> "{\"data\":{\"beneId\":\"\",\"beneficiary_id\":\"bene_from_blank\"}}");
    assertThat(
            blankThenNested
                .createBeneficiary(
                    new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n"))
                .beneficiaryId())
        .isEqualTo("bene_from_blank");
    LiveCashfreePayoutClient nestedXfer =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            mapper,
            req -> "{\"data\":{\"transferId\":\"xfer_nested\",\"status\":\"SUCCESS\"}}");
    assertThat(
            nestedXfer
                .createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", null, "r", Map.of()))
                .payoutId())
        .isEqualTo("xfer_nested");
    LiveCashfreePayoutClient statusError =
        new LiveCashfreePayoutClient(
            "k", "s", mapper, req -> "{\"status\":\"ERROR\",\"message\":\"nope\"}");
    assertThatThrownBy(
            () ->
                statusError.createPayout(
                    new CashfreePayoutClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUTS_UNAVAILABLE");
  }
}
