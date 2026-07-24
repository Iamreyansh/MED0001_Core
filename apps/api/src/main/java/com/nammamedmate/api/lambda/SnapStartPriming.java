package com.nammamedmate.api.lambda;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.crac.Context;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CRaC hooks for AWS Lambda SnapStart. Primes HTTP stack before checkpoint; reconnect guidance
 * after restore.
 */
@Component
public class SnapStartPriming implements Resource {

  private static final Logger log = LoggerFactory.getLogger(SnapStartPriming.class);

  private final int serverPort;

  public SnapStartPriming(@Value("${server.port:8080}") int serverPort) {
    this.serverPort = serverPort;
    try {
      org.crac.Core.getGlobalContext().register(this);
    } catch (Throwable ignored) {
      // CRaC not available outside SnapStart — safe to ignore
    }
  }

  @Override
  public void beforeCheckpoint(Context<? extends Resource> context) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://127.0.0.1:" + serverPort + "/api/v1/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      client.send(request, HttpResponse.BodyHandlers.discarding());
      log.info("SnapStart priming completed");
    } catch (Exception e) {
      log.warn("SnapStart priming skipped: {}", e.toString());
    }
  }

  @Override
  public void afterRestore(Context<? extends Resource> context) {
    log.info("SnapStart restore — reconnect DB/Redis pools on first use");
  }
}
