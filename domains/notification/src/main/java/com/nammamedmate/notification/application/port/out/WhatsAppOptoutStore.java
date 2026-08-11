package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.WhatsAppOptout;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppOptoutStore {

  boolean isActivelyOptedOut(String phone);

  void upsertActive(UUID id, String phone, WhatsAppOptoutSource source, Instant at);

  /** BR-8: re-enable WhatsApp in prefs clears active STOP opt-outs. */
  void deactivateByPhone(String phone);

  Optional<WhatsAppOptout> findActiveByPhone(String phone);
}
