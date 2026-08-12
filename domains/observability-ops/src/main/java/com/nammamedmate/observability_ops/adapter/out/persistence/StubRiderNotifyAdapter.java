package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.observability_ops.application.port.out.RiderNotifyPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Stub rider notify — ids-only outbox; in-memory zone directory for tests. */
@Component
public class StubRiderNotifyAdapter implements RiderNotifyPort {

  private static final Logger log = LoggerFactory.getLogger(StubRiderNotifyAdapter.class);

  private final ObjectProvider<OutboxPublisher> outbox;
  private final Map<UUID, ZoneInfo> zones = new ConcurrentHashMap<>();
  private final List<NotifyRecord> notified = new ArrayList<>();

  public StubRiderNotifyAdapter(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
    UUID def = UUID.fromString("11111111-1111-4111-8111-111111111111");
    zones.put(def, new ZoneInfo("Whitefield", 8));
  }

  @Override
  public boolean zoneExists(UUID zoneId) {
    return zones.containsKey(zoneId);
  }

  @Override
  public String zoneName(UUID zoneId) {
    ZoneInfo z = zones.get(zoneId);
    return z == null ? zoneId.toString() : z.name();
  }

  @Override
  public NotifyResult notifyOfflineRiders(UUID zoneId, int maxPerRider, int cooldownHours) {
    ZoneInfo z = zones.get(zoneId);
    if (z == null) {
      return new NotifyResult(0, 0, zoneId.toString());
    }
    int sent = Math.min(z.offlineRiders(), z.offlineRiders());
    synchronized (notified) {
      notified.add(new NotifyRecord(zoneId, sent, maxPerRider, cooldownHours));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("zone_id", zoneId.toString());
    payload.put("max_per_rider", maxPerRider);
    payload.put("cooldown_hours", cooldownHours);
    log.info("observability.remediation.request_riders zone_id={} sent={}", zoneId, sent);
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null) {
      publisher.publish(
          DomainEvent.of("observability.remediation.request_riders", "zone", zoneId, payload));
    }
    return new NotifyResult(sent, sent, z.name());
  }

  public void putZone(UUID id, String name, int offlineRiders) {
    zones.put(id, new ZoneInfo(name, offlineRiders));
  }

  public List<NotifyRecord> notified() {
    synchronized (notified) {
      return List.copyOf(notified);
    }
  }

  public void clearNotified() {
    synchronized (notified) {
      notified.clear();
    }
  }

  public record NotifyRecord(UUID zoneId, int sent, int maxPerRider, int cooldownHours) {}

  private record ZoneInfo(String name, int offlineRiders) {}
}
