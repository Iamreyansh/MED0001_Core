package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CommunicationConfigAudit;
import java.util.List;

public interface CommunicationConfigAuditStore {

  void insert(CommunicationConfigAudit audit);

  List<CommunicationConfigAudit> findByChannel(String channel);
}
