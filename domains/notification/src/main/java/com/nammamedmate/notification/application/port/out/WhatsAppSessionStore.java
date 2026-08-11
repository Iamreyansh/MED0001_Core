package com.nammamedmate.notification.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface WhatsAppSessionStore {

  void upsertCustomerMessage(String phone, Instant at);

  Optional<Instant> lastCustomerMessageAt(String phone);
}
