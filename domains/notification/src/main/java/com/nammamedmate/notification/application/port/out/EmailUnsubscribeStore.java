package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.EmailUnsubscribe;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailUnsubscribeStore {

  void upsertActive(UUID id, String email, EmailUnsubscribeSource source, Instant at);

  boolean isActivelyUnsubscribed(String email);

  Optional<EmailUnsubscribe> findActive(String email);
}
