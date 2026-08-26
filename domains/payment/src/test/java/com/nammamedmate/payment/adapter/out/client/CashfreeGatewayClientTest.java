package com.nammamedmate.payment.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort.CreateOrderResult;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort.RefundResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CashfreeGatewayClientTest {

  @Test
  void stubCreateVerifyAndWebhook() {
    StubCashfreeGatewayClient stub = new StubCashfreeGatewayClient();
    UUID orderId = UUID.randomUUID();
    CreateOrderResult created = stub.createOrder(orderId, 1000);
    assertThat(created.gatewayOrderId()).startsWith("order_stub_");
    assertThat(stub.keyId()).isEqualTo(StubCashfreeGatewayClient.DEFAULT_KEY_ID);

    String sig = stub.signPayment(created.gatewayOrderId(), "pay_1");
    assertThat(stub.verifyPaymentSignature(created.gatewayOrderId(), "pay_1", sig)).isTrue();
    assertThat(stub.verifyPaymentSignature(created.gatewayOrderId(), "pay_1", "bad")).isFalse();
    assertThat(stub.verifyPaymentSignature(null, "pay_1", sig)).isFalse();

    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String wh =
        StubCashfreeGatewayClient.hmacHex(StubCashfreeGatewayClient.DEFAULT_WEBHOOK_SECRET, "{}");
    assertThat(stub.verifyWebhookSignature(wh, body)).isTrue();
    assertThat(stub.verifyWebhookSignature(null, body)).isFalse();

    assertThatThrownBy(() -> stub.createOrder(orderId, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> new StubCashfreeGatewayClient("k", "s", "w", true).createOrder(orderId, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    assertThat(stub.refund("pay_abcdefghijkl", 500).gatewayRefundId()).contains("rfnd_stub_");
    assertThatThrownBy(() -> stub.refund(null, 100))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> stub.refund("pay_1", 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> new StubCashfreeGatewayClient("k", "s", "w", true).refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");
    assertThat(stub.refund("short", 10).gatewayRefundId()).contains("short");
    assertThatThrownBy(() -> stub.refund("   ", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void stubBlankSecretsFallBack() {
    StubCashfreeGatewayClient stub = new StubCashfreeGatewayClient(" ", " ", " ");
    assertThat(stub.keyId()).isEqualTo(StubCashfreeGatewayClient.DEFAULT_KEY_ID);
    stub.createOrder(UUID.randomUUID(), 50);
  }

  @Test
  void liveCreateAndVerify() {
    ObjectMapper om = new ObjectMapper();
    AtomicReference<LiveCashfreeGatewayClient.Request> seen = new AtomicReference<>();
    Function<LiveCashfreeGatewayClient.Request, String> http =
        req -> {
          seen.set(req);
          return "{\"id\":\"order_live_1\",\"amount\":500}";
        };
    LiveCashfreeGatewayClient live =
        new LiveCashfreeGatewayClient("key", "secret", "whsec", om, http);
    CreateOrderResult result = live.createOrder(UUID.randomUUID(), 500);
    assertThat(result.gatewayOrderId()).isEqualTo("order_live_1");
    assertThat(live.keyId()).isEqualTo("key");
    assertThat(seen.get().headers()).containsKey("x-client-id");

    String sig = live.signPayment("order_live_1", "pay_1");
    assertThat(live.verifyPaymentSignature("order_live_1", "pay_1", sig)).isTrue();
    assertThat(live.verifyPaymentSignature(null, "pay_1", sig)).isFalse();
    assertThat(live.verifyWebhookSignature(null, new byte[0])).isFalse();
    String wh = LiveCashfreeGatewayClient.hmacHex("whsec", "body");
    assertThat(live.verifyWebhookSignature(wh, "body".getBytes(StandardCharsets.UTF_8))).isTrue();

    assertThatThrownBy(() -> live.createOrder(UUID.randomUUID(), 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    AtomicReference<LiveCashfreeGatewayClient.Request> refundSeen = new AtomicReference<>();
    LiveCashfreeGatewayClient liveRefund =
        new LiveCashfreeGatewayClient(
            "key",
            "secret",
            "whsec",
            om,
            req -> {
              refundSeen.set(req);
              return "{\"id\":\"rfnd_live_1\"}";
            });
    assertThat(liveRefund.refund("pay_1", 500).gatewayRefundId()).isEqualTo("rfnd_live_1");
    assertThat(refundSeen.get().uri().toString()).contains("/orders/refunds");
    assertThatThrownBy(() -> liveRefund.refund(" ", 500))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> liveRefund.refund(null, 500))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> liveRefund.refund("pay_1", 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void liveCreateRequestBuildFailure() throws Exception {
    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    LiveCashfreeGatewayClient live =
        new LiveCashfreeGatewayClient("k", "s", "w", boom, req -> "{}");
    assertThatThrownBy(() -> live.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");
  }

  @Test
  void stubNullConstructorArgsAndBlankSignature() {
    StubCashfreeGatewayClient stub = new StubCashfreeGatewayClient(null, null, null);
    assertThat(stub.keyId()).isEqualTo(StubCashfreeGatewayClient.DEFAULT_KEY_ID);
    assertThat(stub.verifyPaymentSignature(null, "p", "sig")).isFalse();
    assertThat(stub.verifyPaymentSignature("o", null, "sig")).isFalse();
    assertThat(stub.verifyPaymentSignature("o", "p", null)).isFalse();
    assertThat(stub.verifyPaymentSignature("o", "p", " ")).isFalse();
    assertThat(stub.verifyWebhookSignature(null, new byte[0])).isFalse();
    assertThat(stub.verifyWebhookSignature("x", null)).isFalse();
  }

  @Test
  void liveCreateErrorPaths() {
    ObjectMapper om = new ObjectMapper();
    LiveCashfreeGatewayClient missingId =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{\"amount\":1}");
    assertThatThrownBy(() -> missingId.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    LiveCashfreeGatewayClient whitespaceId =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{\"id\":\"   \"}");
    assertThatThrownBy(() -> whitespaceId.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    LiveCashfreeGatewayClient badJson =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "not-json");
    assertThatThrownBy(() -> badJson.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    LiveCashfreeGatewayClient httpFail =
        new LiveCashfreeGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new RuntimeException("timeout");
            });
    assertThatThrownBy(() -> httpFail.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    LiveCashfreeGatewayClient appFail =
        new LiveCashfreeGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new AppException("CASHFREE_ERROR", "x", 502);
            });
    assertThatThrownBy(() -> appFail.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_ERROR");

    LiveCashfreeGatewayClient refundMissingId =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{}");
    assertThatThrownBy(() -> refundMissingId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");
    LiveCashfreeGatewayClient refundHttp =
        new LiveCashfreeGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new RuntimeException("x");
            });
    assertThatThrownBy(() -> refundHttp.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");
    LiveCashfreeGatewayClient refundApp =
        new LiveCashfreeGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new AppException("CASHFREE_ERROR", "x", 502);
            });
    assertThatThrownBy(() -> refundApp.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");

    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    try {
      when(boom.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
    LiveCashfreeGatewayClient buildFail =
        new LiveCashfreeGatewayClient("k", "s", "w", boom, req -> "{}");
    assertThatThrownBy(() -> buildFail.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");

    LiveCashfreeGatewayClient blankRefundId =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{\"id\":\"  \"}");
    assertThatThrownBy(() -> blankRefundId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");

    LiveCashfreeGatewayClient otherApp =
        new LiveCashfreeGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new AppException("VALIDATION_ERROR", "nope", 400);
            });
    assertThatThrownBy(() -> otherApp.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    LiveCashfreeGatewayClient badRefundJson =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "not-json");
    assertThatThrownBy(() -> badRefundJson.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");

    LiveCashfreeGatewayClient nullId =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{\"id\":null}");
    assertThatThrownBy(() -> nullId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");

    assertThat(LiveCashfreeGatewayClient.hmacHex("s", "p")).isNotBlank();
    LiveCashfreeGatewayClient live =
        new LiveCashfreeGatewayClient("k", "s", "w", om, req -> "{\"id\":\"o\"}");
    assertThat(live.verifyPaymentSignature(null, "p", "sig")).isFalse();
    assertThat(live.verifyPaymentSignature("o", null, "sig")).isFalse();
    assertThat(live.verifyPaymentSignature("o", "p", null)).isFalse();
    assertThat(live.verifyPaymentSignature("o", "p", " ")).isFalse();
    assertThat(live.verifyWebhookSignature(null, new byte[0])).isFalse();
    assertThat(live.verifyWebhookSignature("x", null)).isFalse();
  }

  @Test
  void hmacFailurePath() {
    assertThat(StubCashfreeGatewayClient.hmacHex("s", "p", "HmacSHA256")).isNotBlank();
    assertThatThrownBy(() -> StubCashfreeGatewayClient.hmacHex("s", "p", "NoSuchAlgo"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> LiveCashfreeGatewayClient.hmacHex("s", "p", "NoSuchAlgo"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void liveCashfreeIdFallbacksTimestampBase64AndLiveBase() {
    ObjectMapper om = new ObjectMapper();
    AtomicReference<LiveCashfreeGatewayClient.Request> seen = new AtomicReference<>();
    LiveCashfreeGatewayClient liveMode =
        new LiveCashfreeGatewayClient(
            "key",
            "secret",
            "whsec",
            true,
            om,
            req -> {
              seen.set(req);
              return "{\"order_id\":\"direct_ord\",\"payment_session_id\":\"sess\"}";
            });
    assertThat(liveMode.createOrder(UUID.randomUUID(), 100).gatewayOrderId())
        .isEqualTo("direct_ord");
    assertThat(seen.get().uri().toString()).startsWith("https://api.cashfree.com/pg");

    LiveCashfreeGatewayClient blankThenCf =
        new LiveCashfreeGatewayClient(
            "key",
            "secret",
            "whsec",
            om,
            req -> "{\"order_id\":\"\",\"cf_order_id\":\"cf_ord_1\"}");
    assertThat(blankThenCf.createOrder(UUID.randomUUID(), 100).gatewayOrderId())
        .isEqualTo("cf_ord_1");

    LiveCashfreeGatewayClient missingOrderId =
        new LiveCashfreeGatewayClient(
            "key", "secret", "whsec", om, req -> "{\"cf_order_id\":\"cf_only\"}");
    assertThat(missingOrderId.createOrder(UUID.randomUUID(), 100).gatewayOrderId())
        .isEqualTo("cf_only");

    LiveCashfreeGatewayClient idFallback =
        new LiveCashfreeGatewayClient(
            "key", "secret", "whsec", om, req -> "{\"cf_order_id\":\"  \",\"id\":\"legacy_1\"}");
    assertThat(idFallback.createOrder(UUID.randomUUID(), 100).gatewayOrderId())
        .isEqualTo("legacy_1");

    LiveCashfreeGatewayClient refundDirect =
        new LiveCashfreeGatewayClient(
            "key", "secret", "whsec", om, req -> "{\"refund_id\":\"direct_rfnd\"}");
    assertThat(refundDirect.refund("pay_1", 10).gatewayRefundId()).isEqualTo("direct_rfnd");
    LiveCashfreeGatewayClient refundCf =
        new LiveCashfreeGatewayClient(
            "key",
            "secret",
            "whsec",
            om,
            req -> "{\"refund_id\":\"\",\"cf_refund_id\":\"cf_rfnd\"}");
    assertThat(refundCf.refund("pay_1", 10).gatewayRefundId()).isEqualTo("cf_rfnd");
    LiveCashfreeGatewayClient refundMissing =
        new LiveCashfreeGatewayClient(
            "key", "secret", "whsec", om, req -> "{\"cf_refund_id\":\"cf_only_rfnd\"}");
    assertThat(refundMissing.refund("pay_1", 10).gatewayRefundId()).isEqualTo("cf_only_rfnd");
    LiveCashfreeGatewayClient refundId =
        new LiveCashfreeGatewayClient(
            "key",
            "secret",
            "whsec",
            om,
            req -> "{\"cf_refund_id\":\"  \",\"id\":\"rfnd_legacy\"}");
    assertThat(refundId.refund("pay_1", 10).gatewayRefundId()).isEqualTo("rfnd_legacy");

    LiveCashfreeGatewayClient live =
        new LiveCashfreeGatewayClient("key", "secret", "whsec", om, req -> "{\"id\":\"o\"}");
    byte[] body = "body".getBytes(StandardCharsets.UTF_8);
    String withTs = LiveCashfreeGatewayClient.hmacHex("whsec", "1700000000" + "body");
    assertThat(live.verifyWebhookSignature(withTs, "1700000000", body)).isTrue();
    String b64 =
        java.util.Base64.getEncoder()
            .encodeToString(LiveCashfreeGatewayClient.hmacBytes("whsec", "body", "HmacSHA256"));
    assertThat(live.verifyWebhookSignature(b64, body)).isTrue();
    assertThat(live.verifyWebhookSignature("not-a-signature", body)).isFalse();

    var nullHeaders =
        new LiveCashfreeGatewayClient.Request(java.net.URI.create("http://x"), null, "{}");
    assertThat(nullHeaders.headers()).isEmpty();
  }

  @Test
  void stubTimestampBase64AndPortDefault() {
    StubCashfreeGatewayClient stub = new StubCashfreeGatewayClient();
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String withTs =
        StubCashfreeGatewayClient.hmacHex(StubCashfreeGatewayClient.DEFAULT_WEBHOOK_SECRET, "ts{}");
    assertThat(stub.verifyWebhookSignature(withTs, "ts", body)).isTrue();
    String b64 =
        java.util.Base64.getEncoder()
            .encodeToString(
                StubCashfreeGatewayClient.hmacBytes(
                    StubCashfreeGatewayClient.DEFAULT_WEBHOOK_SECRET, "{}", "HmacSHA256"));
    assertThat(stub.verifyWebhookSignature(b64, body)).isTrue();

    CashfreeGatewayPort port =
        new CashfreeGatewayPort() {
          @Override
          public CreateOrderResult createOrder(UUID medmateOrderId, long amountPaise) {
            return new CreateOrderResult("o", amountPaise, "app");
          }

          @Override
          public boolean verifyPaymentSignature(
              String gatewayOrderId, String paymentId, String signature) {
            return false;
          }

          @Override
          public String signPayment(String gatewayOrderId, String paymentId) {
            return "";
          }

          @Override
          public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
            return signatureHeader != null && rawBody != null;
          }

          @Override
          public String keyId() {
            return "app";
          }

          @Override
          public RefundResult refund(String gatewayPaymentId, long amountPaise) {
            return new RefundResult("r", amountPaise);
          }
        };
    assertThat(port.verifyWebhookSignature("sig", "ts", new byte[0])).isTrue();
    assertThat(port.createOrder(UUID.randomUUID(), 1).keyId()).isEqualTo("app");
  }
}
