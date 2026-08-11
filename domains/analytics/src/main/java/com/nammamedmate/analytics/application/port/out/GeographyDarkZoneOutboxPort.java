package com.nammamedmate.analytics.application.port.out;

import java.util.UUID;

/** Publishes dark-zone alerts for EPIC-020 (ids-only payload). */
public interface GeographyDarkZoneOutboxPort {

  void publishDarkZone(UUID zoneId);
}
