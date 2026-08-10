package com.nammamedmate.teleconsult.application.port.out;

import java.util.UUID;

/** Outbox-only push notifications for teleconsult (no PII phones in payload). */
public interface NotificationDispatchPort {

  void notifyConsultAutoCancelled(UUID customerId, UUID consultId);

  void notifyConsultStatusUpdated(UUID customerId, UUID consultId, String status);
}
