package com.nammamedmate.auth.adapter.out.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class Msg91OtpSmsSenderTest {

  @Test
  void requiresKeyAndSends() {
    assertThatThrownBy(() -> new Msg91OtpSmsSender(" ")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Msg91OtpSmsSender((String) null))
        .isInstanceOf(IllegalStateException.class);
    Msg91OtpSmsSender ok = new Msg91OtpSmsSender("key", (p, o, k) -> 204);
    ok.sendOtp("+919876543210", "123456");
    assertThatThrownBy(() -> new Msg91OtpSmsSender("key", (p, o, k) -> 500).sendOtp("+91", "1"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new Msg91OtpSmsSender("live-key")).isNotNull();
    assertThatThrownBy(
            () ->
                new Msg91OtpSmsSender(
                        "live-key", Msg91OtpSmsSender.defaultSender("http://127.0.0.1:1/"))
                    .sendOtp("+919876543210", "000000"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void httpPostPostsAuthkeyAndFailsClosed() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          assertThat(ex.getRequestHeaders().getFirst("authkey")).isEqualTo("k");
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      assertThat(Msg91OtpSmsSender.httpPost("+919876543210", "12\"34", "k", url)).isEqualTo(204);
      new Msg91OtpSmsSender("k", Msg91OtpSmsSender.defaultSender(url))
          .sendOtp("+919876543210", "123456");
    } finally {
      server.stop(0);
    }
    assertThat(Msg91OtpSmsSender.httpPost("+91", null, "k", "http://127.0.0.1:1/")).isEqualTo(599);
  }
}
