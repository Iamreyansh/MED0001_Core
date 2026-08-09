package com.nammamedmate.payment.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RazorpayXPayoutClientTest {

  @Test
  void stubReturnsPayoutId() {
    StubRazorpayXPayoutClient stub = new StubRazorpayXPayoutClient();
    var result =
        stub.initiatePayout(
            new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1000, "4521", "HDFC0001"));
    assertThat(result.razorpayxPayoutId()).startsWith("pout_stub_");
    assertThat(result.estimatedCreditHours()).isEqualTo(4);
  }

  @Test
  void stubCanFail() {
    StubRazorpayXPayoutClient stub = new StubRazorpayXPayoutClient(true);
    assertThatThrownBy(
            () ->
                stub.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "IFSC")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
  }

  @Test
  void liveParsesPayoutId() {
    LiveRazorpayXPayoutClient live =
        new LiveRazorpayXPayoutClient(
            "key", "secret", new ObjectMapper(), req -> "{\"id\":\"pout_live_1\"}");
    var result =
        live.initiatePayout(
            new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 5000, "9999", "SBIN0001"));
    assertThat(result.razorpayxPayoutId()).isEqualTo("pout_live_1");
  }

  @Test
  void liveRejectsZeroAmountAndBadResponse() {
    LiveRazorpayXPayoutClient live =
        new LiveRazorpayXPayoutClient("k", "s", new ObjectMapper(), req -> "{}");
    assertThatThrownBy(
            () ->
                live.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 0, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                live.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
  }

  @Test
  void livePropagatesHttpFailure() {
    LiveRazorpayXPayoutClient live =
        new LiveRazorpayXPayoutClient(
            "k",
            "s",
            new ObjectMapper(),
            req -> {
              throw new AppException("RAZORPAY_PAYOUT_FAILED", "http", 502);
            });
    assertThatThrownBy(
            () ->
                live.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, null, "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
  }

  @Test
  void liveRuntimeAndInvalidJsonAndNullHeaders() throws Exception {
    LiveRazorpayXPayoutClient runtime =
        new LiveRazorpayXPayoutClient(
            "k",
            "s",
            new ObjectMapper(),
            req -> {
              throw new RuntimeException("net");
            });
    assertThatThrownBy(
            () ->
                runtime.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");

    LiveRazorpayXPayoutClient badJson =
        new LiveRazorpayXPayoutClient("k", "s", new ObjectMapper(), req -> "not-json");
    assertThatThrownBy(
            () ->
                badJson.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");

    ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    LiveRazorpayXPayoutClient encodeFail =
        new LiveRazorpayXPayoutClient("k", "s", failing, req -> "{}");
    assertThatThrownBy(
            () ->
                encodeFail.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");

    LiveRazorpayXPayoutClient blankId =
        new LiveRazorpayXPayoutClient("k", "s", new ObjectMapper(), req -> "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankId.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");

    var req = new LiveRazorpayXPayoutClient.Request(java.net.URI.create("http://x"), null, "{}");
    assertThat(req.headers()).isEmpty();
  }
}
