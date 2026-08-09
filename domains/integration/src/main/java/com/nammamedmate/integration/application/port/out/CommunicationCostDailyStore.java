package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CommunicationCostDaily;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CommunicationCostDailyStore {

  Optional<CommunicationCostDaily> find(LocalDate date, String channel, String provider);

  List<CommunicationCostDaily> findByDate(LocalDate date);

  List<CommunicationCostDaily> findByChannelAndDateRange(
      String channel, LocalDate fromInclusive, LocalDate toInclusive);

  void upsertIncrement(
      LocalDate date,
      String channel,
      String provider,
      int sentDelta,
      int deliveredDelta,
      int fallbackDelta,
      BigDecimal costDelta);
}
