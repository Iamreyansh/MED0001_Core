package com.nammamedmate.observability_ops.application.port.out;

import java.util.UUID;

public interface RiderNotifyPort {

  record NotifyResult(int ridersNotified, int notificationsSent, String zoneName) {}

  boolean zoneExists(UUID zoneId);

  String zoneName(UUID zoneId);

  NotifyResult notifyOfflineRiders(UUID zoneId, int maxPerRider, int cooldownHours);
}
