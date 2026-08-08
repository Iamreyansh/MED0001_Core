package com.nammamedmate.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.FeatureFlagService.CheckResult;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.FeatureFlagCachePort;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.EnvCounts;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.FeatureFlagRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeatureFlagServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  private FeatureFlagStore store;
  private FeatureFlagCachePort cache;
  private RecordingAudit audit;
  private InMemoryRateLimiter rateLimiter;
  private FeatureFlagService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;

  @BeforeEach
  void setUp() {
    store = mock(FeatureFlagStore.class);
    cache = mock(FeatureFlagCachePort.class);
    audit = new RecordingAudit();
    rateLimiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new FeatureFlagService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), "production");
    UUID superId = Ids.newId();
    UUID opsId = Ids.newId();
    superAdmin = new MedmatePrincipal(superId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    opsAdmin = new MedmatePrincipal(opsId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j2");
  }

  @Test
  void ac1_killSwitchAuditsBeforeAfterAndInvalidates() {
    UUID id = Ids.newId();
    FeatureFlagRow before = row(id, "new_checkout_flow", "production", true, 50, "gradual");
    FeatureFlagRow after = row(id, "new_checkout_flow", "production", false, 50, "gradual");
    when(store.findByNameAndEnvironment("new_checkout_flow", "production"))
        .thenReturn(Optional.of(before));
    when(store.update(eq(id), eq(false), eq(50), eq("gradual"), eq(superAdmin.subject()), eq(NOW)))
        .thenReturn(after);

    Map<String, Object> data =
        service.update(superAdmin, "new_checkout_flow", "production", false, null, null);

    assertThat(data).containsEntry("enabled", false).containsEntry("rollout_percentage", 50);
    assertThat(audit.actions).containsExactly("feature_flag.updated");
    assertThat(audit.befores.get(0))
        .containsEntry("enabled", true)
        .containsEntry("rollout_percentage", 50);
    assertThat(audit.afters.get(0)).containsEntry("enabled", false);
    verify(cache).invalidate("production");
  }

  @Test
  void ac2_nonSuperPatchProductionForbidden() {
    assertThatThrownBy(
            () -> service.update(opsAdmin, "cod_enabled", "production", false, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    verify(store, never()).findByNameAndEnvironment(any(), any());
  }

  @Test
  void stagingPatchAllowedForOps() {
    UUID id = Ids.newId();
    FeatureFlagRow before = row(id, "cod_enabled", "staging", true, 100, null);
    FeatureFlagRow after = row(id, "cod_enabled", "staging", false, 100, "off");
    when(store.findByNameAndEnvironment("cod_enabled", "staging")).thenReturn(Optional.of(before));
    when(store.update(eq(id), eq(false), eq(100), eq("off"), eq(opsAdmin.subject()), eq(NOW)))
        .thenReturn(after);

    Map<String, Object> data =
        service.update(opsAdmin, "cod_enabled", "staging", false, null, "off");
    assertThat(data).containsEntry("environment", "staging");
    verify(cache).invalidate("staging");
  }

  @Test
  void ac3_publicCheckReturnsBaseEnabled() {
    when(cache.get("production")).thenReturn(Optional.empty());
    when(store.listByEnvironment("production"))
        .thenReturn(
            List.of(
                row(Ids.newId(), "cod_enabled", "production", true, 100, null),
                row(Ids.newId(), "new_checkout_flow", "production", true, 50, null),
                row(Ids.newId(), "ai_rx_auto_fill", "production", false, 0, null)));

    CheckResult result =
        service.check("cod_enabled,new_checkout_flow,ai_rx_auto_fill", null, "10.0.0.1");

    assertThat(result.flags())
        .containsEntry("cod_enabled", true)
        .containsEntry("new_checkout_flow", true)
        .containsEntry("ai_rx_auto_fill", false);
    assertThat(result.evaluatedAt()).isEqualTo(NOW);
    verify(cache).put(eq("production"), any());
  }

  @Test
  void ac4_rollout150ValidationError() {
    assertThatThrownBy(() -> service.update(superAdmin, "cod_enabled", null, null, 150, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void ac5_redisInvalidateOnRollout100() {
    UUID id = Ids.newId();
    FeatureFlagRow before = row(id, "new_checkout_flow", "production", true, 50, null);
    FeatureFlagRow after = row(id, "new_checkout_flow", "production", true, 100, "done");
    when(store.findByNameAndEnvironment("new_checkout_flow", "production"))
        .thenReturn(Optional.of(before));
    when(store.update(eq(id), eq(true), eq(100), eq("done"), eq(superAdmin.subject()), eq(NOW)))
        .thenReturn(after);

    service.update(superAdmin, "new_checkout_flow", null, null, 100, "done");
    verify(cache).invalidate("production");
  }

  @Test
  void ac6_summaryCounts() {
    when(store.listAll())
        .thenReturn(
            List.of(
                row(Ids.newId(), "a", "production", true, 100, null),
                row(Ids.newId(), "b", "production", true, 50, null),
                row(Ids.newId(), "z", "production", true, 0, null),
                row(Ids.newId(), "c", "production", false, 0, null),
                row(Ids.newId(), "a", "staging", true, 100, null)));
    when(store.countByEnvironment())
        .thenReturn(
            List.of(
                new EnvCounts("production", 4, 3),
                new EnvCounts("staging", 1, 1),
                new EnvCounts("development", 0, 0)));

    Map<String, Object> summary = service.summary(superAdmin);
    assertThat(summary)
        .containsEntry("total", 4L)
        .containsEntry("enabled", 3L)
        .containsEntry("disabled", 1L)
        .containsEntry("partial_rollout", 1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> envs = (Map<String, Object>) summary.get("environments");
    assertThat(envs).containsKeys("production", "staging", "development");
  }

  @Test
  void listAndAuthAndValidationBranches() {
    when(store.listByEnvironment("development")).thenReturn(List.of());
    assertThat(service.list(superAdmin, "development")).isEmpty();

    when(store.listByEnvironment("production"))
        .thenReturn(List.of(row(Ids.newId(), "cod_enabled", "production", true, 100, null)));
    assertThat(service.list(superAdmin, null).get(0).get("updated_by")).isNull();

    assertThatThrownBy(() -> service.list(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c"),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(superAdmin, "prod"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.update(superAdmin, "x", null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, " ", null, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, null, null, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, "x", null, null, -1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(superAdmin, "x", null, true, null, "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findByNameAndEnvironment("missing", "production")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(superAdmin, "missing", null, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FLAG_NOT_FOUND");

    for (AuthRole role :
        List.of(AuthRole.ADMIN_FINANCE, AuthRole.ADMIN_SUPPORT, AuthRole.ADMIN_COMPLIANCE)) {
      when(store.listByEnvironment("production")).thenReturn(List.of());
      assertThat(
              service
                  .list(new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "r"), "")
                  .size())
          .isZero();
    }
  }

  @Test
  void checkUsesCacheAndValidatesFlags() {
    when(cache.get("staging")).thenReturn(Optional.of(Map.of("cod_enabled", true)));
    CheckResult hit = service.check("cod_enabled,unknown", "staging", "1.1.1.1");
    assertThat(hit.flags()).containsEntry("cod_enabled", true).containsEntry("unknown", false);
    verify(store, never()).listByEnvironment("staging");

    assertThatThrownBy(() -> service.check(null, null, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.check("", null, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.check(" , , ", null, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notesBlankBecomesNullAndWriteEnvBlankDefaults() {
    FeatureFlagService svc =
        new FeatureFlagService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), "  ");
    UUID id = Ids.newId();
    FeatureFlagRow before = row(id, "cod_enabled", "production", true, 100, "old");
    AtomicReference<String> notesArg = new AtomicReference<>();
    when(store.findByNameAndEnvironment("cod_enabled", "production"))
        .thenReturn(Optional.of(before));
    when(store.update(any(), anyBoolean(), anyInt(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              notesArg.set(inv.getArgument(3));
              return row(id, "cod_enabled", "production", true, 100, null);
            });
    svc.update(superAdmin, "cod_enabled", null, null, null, "   ");
    assertThat(notesArg.get()).isNull();

    FeatureFlagService blankProp =
        new FeatureFlagService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), null);
    assertThatThrownBy(
            () -> blankProp.update(opsAdmin, "cod_enabled", "production", false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void listIncludesUpdatedByObject() {
    UUID by = Ids.newId();
    FeatureFlagRow row =
        new FeatureFlagRow(
            Ids.newId(),
            "cod_enabled",
            "desc",
            "production",
            true,
            100,
            null,
            by,
            "Ayesha",
            NOW,
            NOW);
    when(store.listByEnvironment("production")).thenReturn(List.of(row));
    Map<String, Object> item = service.list(superAdmin, null).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> updatedBy = (Map<String, Object>) item.get("updated_by");
    assertThat(updatedBy).containsEntry("id", by).containsEntry("name", "Ayesha");

    FeatureFlagRow noName =
        new FeatureFlagRow(
            Ids.newId(),
            "ai_rx_auto_fill",
            "desc",
            "production",
            false,
            0,
            null,
            by,
            null,
            NOW,
            NOW);
    when(store.listByEnvironment("production")).thenReturn(List.of(noName));
    @SuppressWarnings("unchecked")
    Map<String, Object> by2 =
        (Map<String, Object>) service.list(superAdmin, "production").get(0).get("updated_by");
    assertThat(by2).containsEntry("name", "");
  }

  @Test
  void checkResultCopiesNullFlags() {
    CheckResult empty = new CheckResult(null, NOW);
    assertThat(empty.flags()).isEmpty();
  }

  @Test
  void rateLimitOnCheck() {
    RateLimiter alwaysDeny =
        new RateLimiter() {
          @Override
          public boolean tryAcquire(String key, int limit, int windowSeconds) {
            return false;
          }

          @Override
          public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
            return 3;
          }

          @Override
          public void putCooldown(String key, int ttlSeconds) {}

          @Override
          public int cooldownRemainingSeconds(String key) {
            return 0;
          }
        };
    FeatureFlagService limited =
        new FeatureFlagService(
            store, cache, audit, alwaysDeny, Clock.fixed(NOW, ZoneOffset.UTC), "production");
    assertThatThrownBy(() -> limited.check("cod_enabled", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  private static FeatureFlagRow row(
      UUID id, String name, String env, boolean enabled, int rollout, String notes) {
    return new FeatureFlagRow(id, name, "desc", env, enabled, rollout, notes, null, null, NOW, NOW);
  }

  private static final class RecordingAudit implements AdminAuditAppendPort {
    final List<String> actions = new ArrayList<>();
    final List<Map<String, Object>> befores = new ArrayList<>();
    final List<Map<String, Object>> afters = new ArrayList<>();

    @Override
    public void append(
        String entityType,
        UUID actorId,
        String actorRole,
        UUID entityId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
      actions.add(action);
      befores.add(before);
      afters.add(after);
    }
  }
}
