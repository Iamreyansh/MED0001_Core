package com.nammamedmate.observability_ops.application;

import com.nammamedmate.observability_ops.application.port.out.IncidentNumberPort;
import com.nammamedmate.observability_ops.application.port.out.IncidentStore;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class InMemoryIncidentStores {

  static final class Numbers implements IncidentNumberPort {
    private final ConcurrentHashMap<String, AtomicLong> seq = new ConcurrentHashMap<>();

    @Override
    public String next(LocalDate day) {
      String ymd = DateTimeFormatter.BASIC_ISO_DATE.format(day);
      long n = seq.computeIfAbsent(ymd, k -> new AtomicLong(0)).incrementAndGet();
      return "INC-" + ymd + "-" + String.format("%03d", n);
    }
  }

  static final class Incidents implements IncidentStore {
    private final Map<UUID, Incident> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Incident> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Incident> findBySourceAlertId(UUID alertId) {
      return byId.values().stream()
          .filter(i -> alertId != null && alertId.equals(i.sourceAlertId()))
          .findFirst();
    }

    @Override
    public Incident insert(Incident incident) {
      byId.put(incident.id(), incident);
      return incident;
    }

    @Override
    public Incident update(Incident incident) {
      byId.put(incident.id(), incident);
      return incident;
    }

    @Override
    public Page list(
        IncidentStatus status,
        IncidentSeverity severity,
        Instant dateFrom,
        Instant dateTo,
        int page,
        int limit) {
      List<Incident> filtered =
          byId.values().stream()
              .filter(i -> status == null || i.status() == status)
              .filter(i -> severity == null || i.severity() == severity)
              .filter(i -> dateFrom == null || !i.detectedAt().isBefore(dateFrom))
              .filter(i -> dateTo == null || !i.detectedAt().isAfter(dateTo))
              .sorted(Comparator.comparing(Incident::detectedAt).reversed())
              .toList();
      int from = Math.max(0, (page - 1) * limit);
      List<Incident> slice =
          from >= filtered.size()
              ? List.of()
              : filtered.subList(from, Math.min(from + limit, filtered.size()));
      return new Page(new ArrayList<>(slice), filtered.size());
    }

    @Override
    public int countP1P2Between(Instant fromInclusive, Instant toExclusive) {
      return (int)
          byId.values().stream()
              .filter(
                  i -> i.severity() == IncidentSeverity.P1 || i.severity() == IncidentSeverity.P2)
              .filter(i -> !i.detectedAt().isBefore(fromInclusive))
              .filter(i -> i.detectedAt().isBefore(toExclusive))
              .count();
    }

    @Override
    public List<Incident> findResolvedAwaitingPostmortemReminder(Instant resolvedBefore) {
      return byId.values().stream()
          .filter(Incident::isResolved)
          .filter(Incident::postmortemRequired)
          .filter(i -> !i.postmortemFiled())
          .filter(i -> i.postmortemReminderSentAt() == null)
          .filter(i -> i.resolvedAt() != null && !i.resolvedAt().isAfter(resolvedBefore))
          .toList();
    }
  }

  private InMemoryIncidentStores() {}
}
