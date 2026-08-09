package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CommunicationChannelConfig;
import java.util.List;
import java.util.Optional;

public interface CommunicationChannelConfigStore {

  List<CommunicationChannelConfig> findAll();

  Optional<CommunicationChannelConfig> findByChannel(String channel);

  void update(CommunicationChannelConfig config);

  void resetAllDailySentCounts();
}
