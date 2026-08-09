package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StubAndLiveClientTest {

  @Test
  void stubWebhookAndUpi() {
    StubRazorpayClient client = new StubRazorpayClient();
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String sig = RazorpayHmac.hmacHex(StubRazorpayClient.DEFAULT_WEBHOOK_SECRET, "{}");
    assertThat(client.verifyWebhookSignature(sig, body)).isTrue();
    assertThat(client.verifyWebhookSignature("x", body)).isFalse();
    assertThat(client.verifyWebhookSignature(null, body)).isFalse();
    assertThat(client.verifyUpi("user@okaxis").valid()).isTrue();
    assertThat(client.verifyUpi("user@okicici").name()).contains("USER");
    assertThat(client.mode()).isEqualTo("TEST");
    RazorpayClientPort.CreateOrderResult order = client.createOrder(500, "INR", "r1", Map.of());
    assertThat(order.razorpayOrderId()).startsWith("order_stub_");
    assertThat(client.createOrder(500, null, "r1", Map.of()).currency()).isEqualTo("INR");
    assertThat(client.capturePayment("pay_1", 500).status()).isEqualTo("captured");
  }

  @Test
  void stubFailPaths() {
    StubRazorpayClient fail = new StubRazorpayClient("sec", true);
    assertThatThrownBy(() -> fail.createOrder(100, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    StubRazorpayXClient xFail = new StubRazorpayXClient(true, true, true);
    assertThatThrownBy(
            () ->
                xFail.createFundAccount(
                    new RazorpayXClientPort.CreateFundAccountRequest(
                        "PHARMACY", "e", "b", "123456789", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                xFail.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 100, "IMPS", "payout", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void liveRazorpayClientHappyAndErrors() {
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> lastUrl = new AtomicReference<>();
    LiveRazorpayClient client =
        new LiveRazorpayClient(
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
              if (req.uri().getPath().contains("validate/vpa")) {
                return "{\"success\":true,\"customer_name\":\"TEST USER\"}";
              }
              return "{}";
            });
    assertThat(client.mode()).isEqualTo("LIVE");
    assertThat(client.createOrder(100, "INR", "r", Map.of("a", "b")).razorpayOrderId())
        .isEqualTo("order_live");
    assertThat(client.capturePayment("pay_1", 100).status()).isEqualTo("captured");
    assertThat(client.verifyUpi("a@okicici").name()).isEqualTo("TEST USER");
    String sig = RazorpayHmac.hmacHex("wh", "{}");
    assertThat(client.verifyWebhookSignature(sig, "{}".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(lastUrl.get()).contains("api.razorpay.com/v1");

    LiveRazorpayClient bad =
        new LiveRazorpayClient(
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
        .isEqualTo("RAZORPAY_UNAVAILABLE");
  }

  @Test
  void liveRazorpayXClientHappyAndBalanceError() {
    ObjectMapper mapper = new ObjectMapper();
    LiveRazorpayXClient client =
        new LiveRazorpayXClient(
            "xk",
            "xs",
            mapper,
            req -> {
              if (req.uri().getPath().endsWith("/contacts")) {
                return "{\"id\":\"cont_1\"}";
              }
              if (req.uri().getPath().endsWith("/fund_accounts")) {
                return "{\"id\":\"fa_1\"}";
              }
              if (req.uri().getPath().endsWith("/payouts")) {
                return "{\"id\":\"pout_1\",\"status\":\"processing\"}";
              }
              return "{}";
            });
    RazorpayXClientPort.FundAccountResult fa =
        client.createFundAccount(
            new RazorpayXClientPort.CreateFundAccountRequest(
                "PHARMACY", "e1", "HDFC", "50100123456789", "HDFC0001234", "Name"));
    assertThat(fa.contactId()).isEqualTo("cont_1");
    assertThat(fa.fundAccountId()).isEqualTo("fa_1");
    assertThat(
            client
                .createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa_1", 1000, "IMPS", "payout", "ref", Map.of("k", "v")))
                .payoutId())
        .isEqualTo("pout_1");

    LiveRazorpayXClient bal =
        new LiveRazorpayXClient(
            "xk",
            "xs",
            mapper,
            req ->
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"insufficient balance\"}}");
    assertThatThrownBy(
            () ->
                bal.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "payout", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void hmacThrowsOnBogusAlgorithm() {
    assertThat(RazorpayHmac.hmacHex("s", "p")).hasSize(64);
    assertThatThrownBy(() -> RazorpayHmac.hmacHex("s", "p", "nope"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void stubConstructorsAndCaptureValidation() {
    StubRazorpayClient nullSecret = new StubRazorpayClient(null);
    assertThat(nullSecret.mode()).isEqualTo("TEST");
    StubRazorpayClient blankSecret = new StubRazorpayClient(" ");
    assertThat(blankSecret.verifyWebhookSignature(null, null)).isFalse();
    assertThat(blankSecret.verifyWebhookSignature("x", null)).isFalse();
    assertThat(blankSecret.verifyUpi("nope").valid()).isFalse();
    StubRazorpayClient fail = new StubRazorpayClient("sec", true);
    assertThatThrownBy(() -> fail.capturePayment("pay", 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    StubRazorpayClient ok = new StubRazorpayClient();
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
    new StubRazorpayXClient(true);
  }

  @Test
  void liveClientsErrorBranches() {
    ObjectMapper mapper = new ObjectMapper();
    LiveRazorpayClient missingId =
        new LiveRazorpayClient("k", "s", "w", null, mapper, req -> "{\"status\":\"created\"}");
    assertThat(missingId.mode()).isEqualTo("TEST");
    assertThatThrownBy(() -> missingId.createOrder(1, "INR", "r", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    LiveRazorpayClient badJson =
        new LiveRazorpayClient(
            "k",
            "s",
            "w",
            "TEST",
            mapper,
            req -> {
              throw new AppException("RAZORPAY_UNAVAILABLE", "x", 503);
            });
    assertThatThrownBy(() -> badJson.createOrder(1, "INR", "r", Map.of()))
        .isInstanceOf(AppException.class);
    LiveRazorpayClient unreadable =
        new LiveRazorpayClient("k", "s", "w", "TEST", mapper, req -> "not-json");
    assertThatThrownBy(() -> unreadable.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    LiveRazorpayClient vpaAlt =
        new LiveRazorpayClient(
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
    assertThat(LiveRazorpayClient.normalizeMode(null)).isEqualTo("TEST");
    assertThat(LiveRazorpayClient.normalizeMode(" ")).isEqualTo("TEST");
    assertThat(LiveRazorpayClient.normalizeMode("live")).isEqualTo("LIVE");
    new LiveRazorpayClient.Request(java.net.URI.create("https://example.com"), null, "{}");
    assertThat(LiveRazorpayXClient.messageMentionsBalance(null)).isFalse();
    assertThat(LiveRazorpayXClient.messageMentionsBalance("ok")).isFalse();
    assertThat(LiveRazorpayXClient.messageMentionsBalance("low balance")).isTrue();
    LiveRazorpayXClient codeBalance =
        new LiveRazorpayXClient(
            "k",
            "s",
            mapper,
            req ->
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"nope\"}}"
                    .replace("BAD_REQUEST_ERROR", "balance_error"));
    assertThatThrownBy(
            () ->
                codeBalance.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");

    LiveRazorpayXClient missingContact = new LiveRazorpayXClient("k", "s", mapper, req -> "{}");
    assertThatThrownBy(
            () ->
                missingContact.createFundAccount(
                    new RazorpayXClientPort.CreateFundAccountRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient missingFa =
        new LiveRazorpayXClient(
            "k",
            "s",
            mapper,
            req -> req.uri().getPath().endsWith("/contacts") ? "{\"id\":\"c1\"}" : "{}");
    assertThatThrownBy(
            () ->
                missingFa.createFundAccount(
                    new RazorpayXClientPort.CreateFundAccountRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient missingPayout = new LiveRazorpayXClient("k", "s", mapper, req -> "{}");
    assertThatThrownBy(
            () ->
                missingPayout.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest("fa", 1, "IMPS", "p", "r", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient runtime =
        new LiveRazorpayXClient(
            "k",
            "s",
            mapper,
            req -> {
              throw new RuntimeException("down");
            });
    assertThatThrownBy(
            () ->
                runtime.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient appEx =
        new LiveRazorpayXClient(
            "k",
            "s",
            mapper,
            req -> {
              throw new AppException("RAZORPAYX_UNAVAILABLE", "balance low", 503);
            });
    assertThatThrownBy(
            () ->
                appEx.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
    LiveRazorpayXClient genericErr =
        new LiveRazorpayXClient(
            "k", "s", mapper, req -> "{\"error\":{\"code\":\"X\",\"description\":\"nope\"}}");
    assertThatThrownBy(
            () ->
                genericErr.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    new LiveRazorpayXClient.Request(java.net.URI.create("https://example.com"), null, "{}");

    ObjectMapper boom = Mockito.mock(ObjectMapper.class);
    try {
      Mockito.when(boom.writeValueAsString(Mockito.any()))
          .thenThrow(new JsonProcessingException("x") {});
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
    LiveRazorpayClient writeFail = new LiveRazorpayClient("k", "s", "w", "TEST", boom, req -> "{}");
    assertThatThrownBy(() -> writeFail.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    LiveRazorpayXClient writeFailX = new LiveRazorpayXClient("k", "s", boom, req -> "{}");
    assertThatThrownBy(
            () ->
                writeFailX.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient unreadableX = new LiveRazorpayXClient("k", "s", mapper, req -> "not-json");
    assertThatThrownBy(
            () ->
                unreadableX.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayClient blankId =
        new LiveRazorpayClient(
            "k", "s", "w", "TEST", mapper, req -> "{\"id\":\"\",\"status\":\"x\"}");
    assertThatThrownBy(() -> blankId.createOrder(1, "INR", "r", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_UNAVAILABLE");
    LiveRazorpayClient vpaEmptyName =
        new LiveRazorpayClient(
            "k", "s", "w", "TEST", mapper, req -> "{\"success\":false,\"customer_name\":\"\"}");
    assertThat(vpaEmptyName.verifyUpi("a@b").valid()).isFalse();
    LiveRazorpayXClient blankContact =
        new LiveRazorpayXClient("k", "s", mapper, req -> "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankContact.createFundAccount(
                    new RazorpayXClientPort.CreateFundAccountRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient blankFa =
        new LiveRazorpayXClient(
            "k",
            "s",
            mapper,
            req -> req.uri().getPath().endsWith("/contacts") ? "{\"id\":\"c1\"}" : "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankFa.createFundAccount(
                    new RazorpayXClientPort.CreateFundAccountRequest(
                        "PHARMACY", "e", "b", "1", "HDFC0001234", "n")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
    LiveRazorpayXClient blankPayout =
        new LiveRazorpayXClient("k", "s", mapper, req -> "{\"id\":\"\",\"status\":\"x\"}");
    assertThatThrownBy(
            () ->
                blankPayout.createPayout(
                    new RazorpayXClientPort.CreatePayoutRequest(
                        "fa", 1, "IMPS", "p", "r", Map.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAYX_UNAVAILABLE");
  }
}
