package com.nammamedmate.payment.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort.CreateOrderResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RazorpayGatewayClientTest {

  @Test
  void stubCreateVerifyAndWebhook() {
    StubRazorpayGatewayClient stub = new StubRazorpayGatewayClient();
    UUID orderId = UUID.randomUUID();
    CreateOrderResult created = stub.createOrder(orderId, 1000);
    assertThat(created.razorpayOrderId()).startsWith("order_stub_");
    assertThat(stub.keyId()).isEqualTo(StubRazorpayGatewayClient.DEFAULT_KEY_ID);

    String sig = stub.signPayment(created.razorpayOrderId(), "pay_1");
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", sig)).isTrue();
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", "bad")).isFalse();
    assertThat(stub.verifyPaymentSignature(null, "pay_1", sig)).isFalse();

    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String wh =
        StubRazorpayGatewayClient.hmacHex(StubRazorpayGatewayClient.DEFAULT_WEBHOOK_SECRET, "{}");
    assertThat(stub.verifyWebhookSignature(wh, body)).isTrue();
    assertThat(stub.verifyWebhookSignature(null, body)).isFalse();

    assertThatThrownBy(() -> stub.createOrder(orderId, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> new StubRazorpayGatewayClient("k", "s", "w", true).createOrder(orderId, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    assertThat(stub.refund("pay_abcdefghijkl", 500).razorpayRefundId()).contains("rfnd_stub_");
    assertThatThrownBy(() -> stub.refund(null, 100))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> stub.refund("pay_1", 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> new StubRazorpayGatewayClient("k", "s", "w", true).refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");
    assertThat(stub.refund("short", 10).razorpayRefundId()).contains("short");
    assertThatThrownBy(() -> stub.refund("   ", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void stubBlankSecretsFallBack() {
    StubRazorpayGatewayClient stub = new StubRazorpayGatewayClient(" ", " ", " ");
    assertThat(stub.keyId()).isEqualTo(StubRazorpayGatewayClient.DEFAULT_KEY_ID);
    stub.createOrder(UUID.randomUUID(), 50);
  }

  @Test
  void liveCreateAndVerify() {
    ObjectMapper om = new ObjectMapper();
    AtomicReference<LiveRazorpayGatewayClient.Request> seen = new AtomicReference<>();
    Function<LiveRazorpayGatewayClient.Request, String> http =
        req -> {
          seen.set(req);
          return "{\"id\":\"order_live_1\",\"amount\":500}";
        };
    LiveRazorpayGatewayClient live =
        new LiveRazorpayGatewayClient("key", "secret", "whsec", om, http);
    CreateOrderResult result = live.createOrder(UUID.randomUUID(), 500);
    assertThat(result.razorpayOrderId()).isEqualTo("order_live_1");
    assertThat(live.keyId()).isEqualTo("key");
    assertThat(seen.get().headers()).containsKey("Authorization");

    String sig = live.signPayment("order_live_1", "pay_1");
    assertThat(live.verifyPaymentSignature("order_live_1", "pay_1", sig)).isTrue();
    assertThat(live.verifyPaymentSignature(null, "pay_1", sig)).isFalse();
    assertThat(live.verifyWebhookSignature(null, new byte[0])).isFalse();
    String wh = LiveRazorpayGatewayClient.hmacHex("whsec", "body");
    assertThat(live.verifyWebhookSignature(wh, "body".getBytes(StandardCharsets.UTF_8))).isTrue();

    assertThatThrownBy(() -> live.createOrder(UUID.randomUUID(), 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    AtomicReference<LiveRazorpayGatewayClient.Request> refundSeen = new AtomicReference<>();
    LiveRazorpayGatewayClient liveRefund =
        new LiveRazorpayGatewayClient(
            "key",
            "secret",
            "whsec",
            om,
            req -> {
              refundSeen.set(req);
              return "{\"id\":\"rfnd_live_1\"}";
            });
    assertThat(liveRefund.refund("pay_1", 500).razorpayRefundId()).isEqualTo("rfnd_live_1");
    assertThat(refundSeen.get().uri().toString()).contains("/payments/pay_1/refund");
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
    LiveRazorpayGatewayClient live =
        new LiveRazorpayGatewayClient("k", "s", "w", boom, req -> "{}");
    assertThatThrownBy(() -> live.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");
  }

  @Test
  void stubNullConstructorArgsAndBlankSignature() {
    StubRazorpayGatewayClient stub = new StubRazorpayGatewayClient(null, null, null);
    assertThat(stub.keyId()).isEqualTo(StubRazorpayGatewayClient.DEFAULT_KEY_ID);
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
    LiveRazorpayGatewayClient missingId =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{\"amount\":1}");
    assertThatThrownBy(() -> missingId.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    LiveRazorpayGatewayClient whitespaceId =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{\"id\":\"   \"}");
    assertThatThrownBy(() -> whitespaceId.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    LiveRazorpayGatewayClient badJson =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "not-json");
    assertThatThrownBy(() -> badJson.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    LiveRazorpayGatewayClient httpFail =
        new LiveRazorpayGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new RuntimeException("timeout");
            });
    assertThatThrownBy(() -> httpFail.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    LiveRazorpayGatewayClient appFail =
        new LiveRazorpayGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new AppException("RAZORPAY_ERROR", "x", 502);
            });
    assertThatThrownBy(() -> appFail.createOrder(UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    LiveRazorpayGatewayClient refundMissingId =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{}");
    assertThatThrownBy(() -> refundMissingId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");
    LiveRazorpayGatewayClient refundHttp =
        new LiveRazorpayGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new RuntimeException("x");
            });
    assertThatThrownBy(() -> refundHttp.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");
    LiveRazorpayGatewayClient refundApp =
        new LiveRazorpayGatewayClient(
            "k",
            "s",
            "w",
            om,
            req -> {
              throw new AppException("RAZORPAY_ERROR", "x", 502);
            });
    assertThatThrownBy(() -> refundApp.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    try {
      when(boom.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
    LiveRazorpayGatewayClient buildFail =
        new LiveRazorpayGatewayClient("k", "s", "w", boom, req -> "{}");
    assertThatThrownBy(() -> buildFail.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    LiveRazorpayGatewayClient blankRefundId =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{\"id\":\"  \"}");
    assertThatThrownBy(() -> blankRefundId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    LiveRazorpayGatewayClient otherApp =
        new LiveRazorpayGatewayClient(
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

    LiveRazorpayGatewayClient badRefundJson =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "not-json");
    assertThatThrownBy(() -> badRefundJson.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    LiveRazorpayGatewayClient nullId =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{\"id\":null}");
    assertThatThrownBy(() -> nullId.refund("pay_1", 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    assertThat(LiveRazorpayGatewayClient.hmacHex("s", "p")).isNotBlank();
    LiveRazorpayGatewayClient live =
        new LiveRazorpayGatewayClient("k", "s", "w", om, req -> "{\"id\":\"o\"}");
    assertThat(live.verifyPaymentSignature(null, "p", "sig")).isFalse();
    assertThat(live.verifyPaymentSignature("o", null, "sig")).isFalse();
    assertThat(live.verifyPaymentSignature("o", "p", null)).isFalse();
    assertThat(live.verifyPaymentSignature("o", "p", " ")).isFalse();
    assertThat(live.verifyWebhookSignature(null, new byte[0])).isFalse();
    assertThat(live.verifyWebhookSignature("x", null)).isFalse();
  }

  @Test
  void hmacFailurePath() {
    assertThatThrownBy(() -> StubRazorpayGatewayClient.hmacHex("s", "p", "NoSuchAlgo"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> LiveRazorpayGatewayClient.hmacHex("s", "p", "NoSuchAlgo"))
        .isInstanceOf(IllegalStateException.class);
  }
}
