package com.nammamedmate.analytics.adapter.out.messaging;

import com.nammamedmate.analytics.application.port.out.GeographyDarkZoneOutboxPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxGeographyDarkZoneAdapter implements GeographyDarkZoneOutboxPort {

  public static final String EVENT_TYPE = "analytics.zone.dark";

  private final OutboxPublisher outbox;

  public OutboxGeographyDarkZoneAdapter(OutboxPublisher outbox) {
    this.outbox = outbox;
  }

  @Override
  public void publishDarkZone(UUID zoneId) {
    outbox.publish(
        DomainEvent.of(EVENT_TYPE, "delivery_zone", zoneId, Map.of("zone_id", zoneId.toString())));
  }
}
