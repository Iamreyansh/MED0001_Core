package com.nammamedmate.api.config;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.AdminSessionRevokePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettingsAuthBridgeConfigTest {

  @Test
  void revokesViaAuthSessionStore() {
    AuthSessionStore sessions = mock(AuthSessionStore.class);
    Instant now = Instant.parse("2026-07-24T02:00:00Z");
    AdminSessionRevokePort port =
        new SettingsAuthBridgeConfig()
            .adminSessionRevokePort(sessions, Clock.fixed(now, ZoneOffset.UTC));
    UUID id = Ids.newId();
    port.revokeAllSessions(id);
    verify(sessions).revokeAllForUser(eq(id), eq(now));
    port.revokeAllSessions(null);
    verify(sessions, never()).revokeAllForUser(eq(null), eq(now));
  }
}
