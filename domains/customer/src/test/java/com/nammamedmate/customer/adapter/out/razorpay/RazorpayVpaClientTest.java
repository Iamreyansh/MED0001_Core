package com.nammamedmate.customer.adapter.out.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RazorpayVpaClientTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void validateVpa_successTrue() {
    RazorpayVpaClient client =
        new RazorpayVpaClient("key", "secret", mapper, req -> "{\"success\":true}");

    assertThat(client.validateVpa("ramesh@okaxis")).isTrue();
  }

  @Test
  void validateVpa_successFalse() {
    RazorpayVpaClient client =
        new RazorpayVpaClient("key", "secret", mapper, req -> "{\"success\":false}");

    assertThat(client.validateVpa("ramesh@okaxis")).isFalse();
  }

  @Test
  void validateVpa_sendsBasicAuth() {
    AtomicReference<RazorpayVpaClient.Request> captured = new AtomicReference<>();
    Function<RazorpayVpaClient.Request, String> http =
        req -> {
          captured.set(req);
          return "{\"success\":true}";
        };
    RazorpayVpaClient client = new RazorpayVpaClient("kid", "sec", mapper, http);

    client.validateVpa("a@ybl");

    RazorpayVpaClient.Request req = captured.get();
    assertThat(req.uri().toString()).contains("address=a%40ybl");
    assertThat(req.headers().get("Authorization")).startsWith("Basic ");
  }

  @Test
  void validateVpa_httpFailure_timeout() {
    RazorpayVpaClient client =
        new RazorpayVpaClient(
            "key",
            "secret",
            mapper,
            req -> {
              throw new IllegalStateException("boom");
            });

    assertThatThrownBy(() -> client.validateVpa("a@ybl"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VPA_VALIDATION_TIMEOUT");
  }

  @Test
  void validateVpa_badJson_failed() {
    RazorpayVpaClient client = new RazorpayVpaClient("key", "secret", mapper, req -> "not-json");

    assertThatThrownBy(() -> client.validateVpa("a@ybl"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VPA_VALIDATION_FAILED");
  }

  @Test
  void validateVpa_rethrowsAppException() {
    RazorpayVpaClient client =
        new RazorpayVpaClient(
            "key",
            "secret",
            mapper,
            req -> {
              throw new AppException("VPA_VALIDATION_TIMEOUT", "timed out", 503);
            });

    assertThatThrownBy(() -> client.validateVpa("a@ybl"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VPA_VALIDATION_TIMEOUT");
  }

  @Test
  void stub_timeoutAndInvalid() {
    StubRazorpayVpaClient stub = new StubRazorpayVpaClient();
    assertThat(stub.validateVpa("good@okaxis")).isTrue();
    assertThat(stub.validateVpa("bad@invalid")).isFalse();
    assertThat(stub.validateVpa("nosecondpart")).isTrue();
    assertThatThrownBy(() -> stub.validateVpa("timeout@okaxis"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VPA_VALIDATION_TIMEOUT");
    assertThatThrownBy(() -> stub.validateVpa("timeoutonly"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VPA_VALIDATION_TIMEOUT");
  }

  @Test
  void requestRecord() {
    RazorpayVpaClient.Request req =
        new RazorpayVpaClient.Request(URI.create("https://example.com"), Map.of("A", "B"));
    assertThat(req.headers()).containsEntry("A", "B");
  }
}
