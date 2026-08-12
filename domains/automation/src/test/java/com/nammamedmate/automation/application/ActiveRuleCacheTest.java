package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.domain.RuleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActiveRuleCacheTest {

  @Test
  void refreshesAfterTtl() {
    UUID id = UUID.randomUUID();
    RuleSnapshot rule = new RuleSnapshot(id, "order_placed", List.of(), List.of(), 300);
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-24T00:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    RuleLookupPort port =
        new RuleLookupPort() {
          @Override
          public java.util.Optional<RuleSnapshot> findById(UUID ruleId) {
            return java.util.Optional.empty();
          }

          @Override
          public List<RuleSnapshot> listActive() {
            return List.of(rule);
          }
        };
    ActiveRuleCache cache = new ActiveRuleCache(port, clock);
    assertThat(cache.findById(id)).isPresent();
    assertThat(cache.forTrigger("order_placed")).hasSize(1);
    now.set(now.get().plus(Duration.ofSeconds(31)));
    assertThat(cache.findById(id)).isPresent();
    cache.forceRefresh();
    assertThat(cache.forTrigger("missing")).isEmpty();
  }
}
