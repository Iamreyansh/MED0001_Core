package com.nammamedmate.settings.application.port.out;

import java.util.UUID;

/** Composition-root bridge into auth session store (no domain→domain compile dep). */
@FunctionalInterface
public interface AdminSessionRevokePort {

  void revokeAllSessions(UUID staffId);
}
