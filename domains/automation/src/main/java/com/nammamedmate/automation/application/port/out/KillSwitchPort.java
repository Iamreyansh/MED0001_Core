package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.KillSwitchChange;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import java.util.Optional;
import java.util.UUID;

public interface KillSwitchPort {

  KillSwitchStatus status();

  void setStatus(KillSwitchStatus next, UUID actorId, String reason);

  Optional<KillSwitchChange> lastChange();
}
