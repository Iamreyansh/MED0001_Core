package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface OtpSessionStore {

  OtpSessionRecord save(OtpSessionRecord session);

  Optional<OtpSessionRecord> findById(UUID id);
}
