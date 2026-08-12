package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.domain.RuleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** In-memory active-rule cache refreshed every 30s (BR-2). */
@Component
public class ActiveRuleCache {

  private static final Duration TTL = Duration.ofSeconds(30);

  private final RuleLookupPort rules;
  private final Clock clock;
  private final AtomicReference<CacheState> state =
      new AtomicReference<>(new CacheState(Map.of(), Map.of(), Instant.EPOCH));

  public ActiveRuleCache(RuleLookupPort rules, Clock clock) {
    this.rules = rules;
    this.clock = clock;
  }

  public Optional<RuleSnapshot> findById(UUID ruleId) {
    refreshIfStale();
    return Optional.ofNullable(state.get().byId().get(ruleId));
  }

  public List<RuleSnapshot> forTrigger(String triggerId) {
    refreshIfStale();
    return state.get().byTrigger().getOrDefault(triggerId, List.of());
  }

  public void forceRefresh() {
    load();
  }

  private void refreshIfStale() {
    Instant now = clock.instant();
    CacheState cur = state.get();
    if (Duration.between(cur.loadedAt(), now).compareTo(TTL) >= 0) {
      load();
    }
  }

  private void load() {
    List<RuleSnapshot> active = rules.listActive();
    Map<UUID, RuleSnapshot> byId = new ConcurrentHashMap<>();
    Map<String, List<RuleSnapshot>> byTrigger = new ConcurrentHashMap<>();
    for (RuleSnapshot rule : active) {
      byId.put(rule.ruleId(), rule);
      byTrigger.computeIfAbsent(rule.triggerId(), k -> new ArrayList<>()).add(rule);
    }
    Map<String, List<RuleSnapshot>> frozen = new ConcurrentHashMap<>();
    byTrigger.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    state.set(new CacheState(Map.copyOf(byId), frozen, clock.instant()));
  }

  private record CacheState(
      Map<UUID, RuleSnapshot> byId, Map<String, List<RuleSnapshot>> byTrigger, Instant loadedAt) {}
}
