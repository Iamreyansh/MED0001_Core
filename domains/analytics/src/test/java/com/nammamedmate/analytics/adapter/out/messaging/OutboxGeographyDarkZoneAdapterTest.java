package com.nammamedmate.analytics.adapter.out.messaging;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.nammamedmate.messaging.OutboxPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxGeographyDarkZoneAdapterTest {

  @Mock OutboxPublisher outbox;

  @Test
  void publishesIdsOnly() {
    UUID zoneId = UUID.randomUUID();
    new OutboxGeographyDarkZoneAdapter(outbox).publishDarkZone(zoneId);
    verify(outbox)
        .publish(
            argThat(
                e ->
                    OutboxGeographyDarkZoneAdapter.EVENT_TYPE.equals(e.type())
                        && zoneId.equals(e.aggregateId())
                        && e.payload().containsKey("zone_id")
                        && e.payload().size() == 1));
  }
}
