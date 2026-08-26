package com.nammamedmate.payment.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort.PayoutRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashfreePayoutClientTest {

  @Test
  void stubReturnsPayoutId() {
    StubCashfreePayoutClient stub = new StubCashfreePayoutClient();
    var result =
        stub.initiatePayout(
            new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1000, "4521", "HDFC0001"));
    assertThat(result.cashfreeTransferId()).startsWith("pout_stub_");
    assertThat(result.estimatedCreditHours()).isEqualTo(4);
  }

  @Test
  void stubCanFail() {
    StubCashfreePayoutClient stub = new StubCashfreePayoutClient(true);
    assertThatThrownBy(
            () ->
                stub.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "IFSC")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
  }

  @Test
  void liveParsesPayoutId() {
    LiveCashfreePayoutClient live =
        new LiveCashfreePayoutClient(
            "key", "secret", new ObjectMapper(), req -> "{\"id\":\"pout_live_1\"}");
    var result =
        live.initiatePayout(
            new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 5000, "9999", "SBIN0001"));
    assertThat(result.cashfreeTransferId()).isEqualTo("pout_live_1");
  }

  @Test
  void liveRejectsZeroAmountAndBadResponse() {
    LiveCashfreePayoutClient live =
        new LiveCashfreePayoutClient("k", "s", new ObjectMapper(), req -> "{}");
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
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
  }

  @Test
  void livePropagatesHttpFailure() {
    LiveCashfreePayoutClient live =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            new ObjectMapper(),
            req -> {
              throw new AppException("CASHFREE_PAYOUT_FAILED", "http", 502);
            });
    assertThatThrownBy(
            () ->
                live.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, null, "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
  }

  @Test
  void liveRuntimeAndInvalidJsonAndNullHeaders() throws Exception {
    LiveCashfreePayoutClient runtime =
        new LiveCashfreePayoutClient(
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
        .isEqualTo("CASHFREE_PAYOUT_FAILED");

    LiveCashfreePayoutClient badJson =
        new LiveCashfreePayoutClient("k", "s", new ObjectMapper(), req -> "not-json");
    assertThatThrownBy(
            () ->
                badJson.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");

    ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    LiveCashfreePayoutClient encodeFail =
        new LiveCashfreePayoutClient("k", "s", failing, req -> "{}");
    assertThatThrownBy(
            () ->
                encodeFail.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");

    LiveCashfreePayoutClient blankId =
        new LiveCashfreePayoutClient("k", "s", new ObjectMapper(), req -> "{\"id\":\"\"}");
    assertThatThrownBy(
            () ->
                blankId.initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");

    var req = new LiveCashfreePayoutClient.Request(java.net.URI.create("http://x"), null, "{}");
    assertThat(req.headers()).isEmpty();
  }

  @Test
  void liveTransferIdFallbacks() {
    LiveCashfreePayoutClient direct =
        new LiveCashfreePayoutClient(
            "k", "s", new ObjectMapper(), req -> "{\"transferId\":\"direct_t1\"}");
    assertThat(
            direct
                .initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I"))
                .cashfreeTransferId())
        .isEqualTo("direct_t1");

    LiveCashfreePayoutClient nested =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            new ObjectMapper(),
            req -> "{\"transferId\":\"\",\"data\":{\"transferId\":\"nested_t1\"}}");
    assertThat(
            nested
                .initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I"))
                .cashfreeTransferId())
        .isEqualTo("nested_t1");

    LiveCashfreePayoutClient missingTop =
        new LiveCashfreePayoutClient(
            "k", "s", new ObjectMapper(), req -> "{\"data\":{\"transferId\":\"nested_only\"}}");
    assertThat(
            missingTop
                .initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I"))
                .cashfreeTransferId())
        .isEqualTo("nested_only");

    LiveCashfreePayoutClient legacyId =
        new LiveCashfreePayoutClient(
            "k",
            "s",
            new ObjectMapper(),
            req -> "{\"data\":{\"transferId\":\"  \"},\"id\":\"legacy_p\"}");
    assertThat(
            legacyId
                .initiatePayout(
                    new PayoutRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "1", "I"))
                .cashfreeTransferId())
        .isEqualTo("legacy_p");
  }
}
