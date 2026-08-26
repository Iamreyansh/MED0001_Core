package com.nammamedmate.marketing.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpBannerImageValidatorTest {

  private HttpServer server;
  private String base;
  private final HttpBannerImageValidator validator = new HttpBannerImageValidator();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok.jpg",
        ex -> {
          byte[] body = "fake".getBytes(StandardCharsets.UTF_8);
          if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(200, -1);
          } else {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
              os.write(body);
            }
          }
          ex.close();
        });
    server.createContext(
        "/big.png",
        ex -> {
          ex.getResponseHeaders().add("Content-Type", "image/png");
          ex.getResponseHeaders().add("Content-Length", String.valueOf(3L * 1024 * 1024));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/missing.jpg",
        ex -> {
          ex.sendResponseHeaders(404, -1);
          ex.close();
        });
    server.createContext(
        "/nohead.jpg",
        ex -> {
          if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
          }
          byte[] body = "x".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "image/jpeg");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
          }
          ex.close();
        });
    server.createContext(
        "/ext-only",
        ex -> {
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/bad.bin",
        ex -> {
          ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
          ex.getResponseHeaders().add("Content-Length", "10");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/png.png",
        ex -> {
          ex.getResponseHeaders().add("Content-Type", "image/png");
          ex.getResponseHeaders().add("Content-Length", "4");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/notype.jpg",
        ex -> {
          ex.getResponseHeaders().add("Content-Length", "4");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/notype.jpeg",
        ex -> {
          ex.getResponseHeaders().add("Content-Length", "4");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/notype.png",
        ex -> {
          ex.getResponseHeaders().add("Content-Length", "4");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/typed-no-ext",
        ex -> {
          ex.getResponseHeaders().add("Content-Type", "image/jpeg; charset=binary");
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void validatesHappyPathAndErrors() {
    assertThatCode(() -> validator.validate(base + "/ok.jpg")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/png.png")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/nohead.jpg")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/notype.jpg")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/notype.jpg?v=1")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/notype.jpeg")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/notype.png")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/typed-no-ext")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(base + "/ok.jpg?v=1")).doesNotThrowAnyException();

    assertThatThrownBy(() -> validator.validate(base + "/big.png"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IMAGE_TOO_LARGE");
    assertThatThrownBy(() -> validator.validate(base + "/missing.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate(base + "/bad.bin"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate(base + "/ext-only"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate(" "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("ftp://x/a.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://127.0.0.1:1/no-listen.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("https://127.0.0.1:1/no-listen.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://10.0.0.1/secret.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://metadata.google.internal/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://foo.internal/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://0.0.0.0/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://224.0.0.1/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://no-such-host.invalid/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://[:::"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http:///x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://169.254.1.1/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
    assertThatThrownBy(() -> validator.validate("http://example.com:70000/x.jpg"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_IMAGE_URL");
  }
}
