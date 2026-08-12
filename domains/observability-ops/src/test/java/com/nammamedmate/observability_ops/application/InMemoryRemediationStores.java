package com.nammamedmate.observability_ops.application;

import com.nammamedmate.observability_ops.application.port.out.RemediationLogStore;
import com.nammamedmate.observability_ops.application.port.out.RemediationPlaybookStore;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationPlaybook;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory remediation stores for unit tests. */
final class InMemoryRemediationStores {

  static final class Playbooks implements RemediationPlaybookStore {
    private final Map<UUID, RemediationPlaybook> byId = new ConcurrentHashMap<>();

    Playbooks() {
      seed(
          UUID.fromString("02000002-0001-4000-8000-000000000001"),
          AlertType.ZONE_DARK,
          RemediationActionType.REQUEST_RIDERS,
          "Send push notifications to offline riders in the dark zone to come online.",
          Map.of(
              "dark_duration_minutes",
              30,
              "max_notifications_per_rider",
              3,
              "notification_cooldown_hours",
              2));
      seed(
          UUID.fromString("02000002-0001-4000-8000-000000000002"),
          AlertType.LOW_FILL_RATE,
          RemediationActionType.THROTTLE_PHARMACY,
          "Throttle pharmacy",
          Map.of(
              "fill_rate_pct",
              70,
              "consecutive_days",
              3,
              "throttle_pct",
              30,
              "recovery_fill_rate_pct",
              80,
              "recovery_consecutive_days",
              2));
      seed(
          UUID.fromString("02000002-0001-4000-8000-000000000003"),
          AlertType.PAYMENT_JOB_FAILURE,
          RemediationActionType.RETRY_PAYMENT_JOB,
          "Retry payment",
          Map.of("retry_delay_minutes", 5, "max_retries", 3));
      seed(
          UUID.fromString("02000002-0001-4000-8000-000000000004"),
          AlertType.API_ERROR_RATE_HIGH,
          RemediationActionType.PAGE_ON_CALL,
          "Page on-call",
          Map.of("error_rate_pct", 5, "window_minutes", 5));
    }

    private void seed(
        UUID id,
        AlertType type,
        RemediationActionType action,
        String desc,
        Map<String, Object> threshold) {
      byId.put(
          id,
          new RemediationPlaybook(
              id,
              type,
              action,
              desc,
              threshold,
              true,
              null,
              null,
              Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Override
    public List<RemediationPlaybook> findAll() {
      return byId.values().stream()
          .sorted(Comparator.comparing(p -> p.alertType().name()))
          .toList();
    }

    @Override
    public Optional<RemediationPlaybook> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RemediationPlaybook> findByAlertType(AlertType alertType) {
      return byId.values().stream().filter(p -> p.alertType() == alertType).findFirst();
    }

    @Override
    public RemediationPlaybook update(
        UUID id,
        boolean enabled,
        Map<String, Object> threshold,
        UUID updatedBy,
        Instant updatedAt) {
      RemediationPlaybook old = byId.get(id);
      RemediationPlaybook neu =
          new RemediationPlaybook(
              old.id(),
              old.alertType(),
              old.autoRemediationAction(),
              old.description(),
              threshold,
              enabled,
              old.lastTriggeredAt(),
              updatedBy,
              updatedAt);
      byId.put(id, neu);
      return neu;
    }

    @Override
    public void touchLastTriggered(UUID id, Instant at) {
      RemediationPlaybook old = byId.get(id);
      if (old != null) {
        byId.put(
            id,
            new RemediationPlaybook(
                old.id(),
                old.alertType(),
                old.autoRemediationAction(),
                old.description(),
                old.threshold(),
                old.enabled(),
                at,
                old.updatedBy(),
                old.updatedAt()));
      }
    }

    void removeByAlertType(AlertType type) {
      byId.entrySet().removeIf(e -> e.getValue().alertType() == type);
    }
  }

  static final class Logs implements RemediationLogStore {
    private final Map<UUID, RemediationLogEntry> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RemediationLogEntry> ordered = new CopyOnWriteArrayList<>();

    @Override
    public RemediationLogEntry insert(RemediationLogEntry entry) {
      byId.put(entry.id(), entry);
      ordered.add(entry);
      return entry;
    }

    @Override
    public void complete(
        UUID id,
        RemediationStatus status,
        Map<String, Object> actionDetails,
        Instant completedAt,
        String errorMessage) {
      RemediationLogEntry old = byId.get(id);
      if (old == null) {
        return;
      }
      RemediationLogEntry neu =
          new RemediationLogEntry(
              old.id(),
              old.alertId(),
              old.playbookId(),
              old.actionType(),
              old.triggerType(),
              old.targetEntityType(),
              old.targetEntityId(),
              actionDetails,
              status,
              old.triggeredBy(),
              old.triggeredAt(),
              completedAt,
              errorMessage);
      byId.put(id, neu);
      ordered.replaceAll(e -> e.id().equals(id) ? neu : e);
    }

    @Override
    public Optional<Instant> lastTriggeredAt(
        RemediationActionType actionType, UUID targetEntityId) {
      return ordered.stream()
          .filter(e -> e.actionType() == actionType && e.targetEntityId().equals(targetEntityId))
          .map(RemediationLogEntry::triggeredAt)
          .max(Comparator.naturalOrder());
    }

    @Override
    public int countByActionAndTargetSince(
        RemediationActionType actionType, UUID targetEntityId, Instant since) {
      return (int)
          ordered.stream()
              .filter(
                  e -> e.actionType() == actionType && e.targetEntityId().equals(targetEntityId))
              .filter(e -> !e.triggeredAt().isBefore(since))
              .count();
    }

    @Override
    public Page list(
        RemediationActionType actionType,
        RemediationStatus status,
        Instant dateFrom,
        Instant dateTo,
        int page,
        int limit) {
      List<RemediationLogEntry> filtered =
          ordered.stream()
              .filter(e -> actionType == null || e.actionType() == actionType)
              .filter(e -> status == null || e.status() == status)
              .filter(e -> dateFrom == null || !e.triggeredAt().isBefore(dateFrom))
              .filter(e -> dateTo == null || !e.triggeredAt().isAfter(dateTo))
              .sorted(Comparator.comparing(RemediationLogEntry::triggeredAt).reversed())
              .toList();
      int from = Math.max(0, (page - 1) * limit);
      List<RemediationLogEntry> slice =
          from >= filtered.size()
              ? List.of()
              : filtered.subList(from, Math.min(from + limit, filtered.size()));
      return new Page(new ArrayList<>(slice), filtered.size());
    }

    List<RemediationLogEntry> all() {
      return List.copyOf(ordered);
    }
  }

  private InMemoryRemediationStores() {}
}
