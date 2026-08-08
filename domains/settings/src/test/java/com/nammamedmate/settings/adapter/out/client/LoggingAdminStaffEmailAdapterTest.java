package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoggingAdminStaffEmailAdapterTest {

  @Test
  void logsInviteAndReset() {
    LoggingAdminStaffEmailAdapter adapter = new LoggingAdminStaffEmailAdapter();
    Instant expires = Instant.parse("2026-07-24T06:00:00Z");
    adapter.sendInvite(Ids.newId(), "a@b.co", "A", "token-invite", expires);
    adapter.sendPasswordReset(Ids.newId(), "a@b.co", "A", "token-reset", expires);
    adapter.sendInvite(Ids.newId(), "a@b.co", "A", null, expires);
    adapter.sendPasswordReset(Ids.newId(), "a@b.co", "A", null, expires);
  }
}
