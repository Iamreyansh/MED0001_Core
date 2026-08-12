package com.nammamedmate.automation.application;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** AC-007: bootstrap calls initialize and logs the result. Gated for api (default on). */
@Component
@ConditionalOnProperty(
    name = "medmate.automation.seed.initialize-on-startup",
    havingValue = "true",
    matchIfMissing = true)
public class SeedAutomationsBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedAutomationsBootstrap.class);

  private final SeedAutomationsService seeds;

  public SeedAutomationsBootstrap(SeedAutomationsService seeds) {
    this.seeds = seeds;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      Map<String, Object> result = seeds.initialize(null);
      log.info(
          "Seed automations initialized: created={} already_existed={} workflows_created={}"
              + " workflows_already_existed={}",
          result.get("created"),
          result.get("already_existed"),
          result.get("workflows_created"),
          result.get("workflows_already_existed"));
    } catch (RuntimeException ex) {
      log.warn("Seed automations initialize skipped: {}", ex.getMessage());
    }
  }
}
