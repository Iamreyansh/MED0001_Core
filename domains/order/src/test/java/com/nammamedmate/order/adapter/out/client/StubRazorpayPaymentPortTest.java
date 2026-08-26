package com.nammamedmate.order.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubRazorpayPaymentPortTest {

  @Test
  void createSignVerifyAndFail() {
    StubRazorpayPaymentPort stub = new StubRazorpayPaymentPort();
    UUID orderId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    var created = stub.createOrder(orderId, 22125);
    assertThat(created.razorpayOrderId()).startsWith("order_stub_");
    assertThat(created.amountPaise()).isEqualTo(22125);

    String sig = stub.signPayment(created.razorpayOrderId(), "pay_1");
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", sig)).isTrue();
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", "nope")).isFalse();
    assertThat(stub.verifyPaymentSignature(null, "pay_1", sig)).isFalse();
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), null, sig)).isFalse();
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", null)).isFalse();
    assertThat(stub.verifyPaymentSignature(created.razorpayOrderId(), "pay_1", " ")).isFalse();

    String body = "{\"event\":\"payment.captured\"}";
    String wh =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, body);
    assertThat(stub.verifyWebhookSignature(wh, body.getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(stub.verifyWebhookSignature(null, body.getBytes(StandardCharsets.UTF_8))).isFalse();

    assertThatThrownBy(() -> stub.createOrder(orderId, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    StubRazorpayPaymentPort fail = new StubRazorpayPaymentPort("k", "w", true);
    assertThatThrownBy(() -> fail.createOrder(orderId, 100))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_INITIATION_FAILED");

    var refunded = stub.refund("pay_abcdefghijkl", 500);
    assertThat(refunded.razorpayRefundId()).startsWith("rfnd_stub_");
    assertThat(refunded.amountPaise()).isEqualTo(500);
    assertThatThrownBy(() -> stub.refund(null, 100))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> stub.refund("pay_1", 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(stub.refund("short", 10).razorpayRefundId()).contains("short");
    assertThat(stub.handleWebhook("sig", "{}".getBytes(StandardCharsets.UTF_8)))
        .containsEntry("processed", false);
  }
}
