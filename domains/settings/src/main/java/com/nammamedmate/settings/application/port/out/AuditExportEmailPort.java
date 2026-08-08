package com.nammamedmate.settings.application.port.out;

import java.util.UUID;

public interface AuditExportEmailPort {

  void sendExportReady(UUID actorId, UUID exportJobId, String downloadUrl);
}
