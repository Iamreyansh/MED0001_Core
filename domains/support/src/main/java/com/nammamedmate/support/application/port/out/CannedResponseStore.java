package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.TicketCategory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CannedResponseStore {

  record ListFilter(TicketCategory category, String q, int offset, int limit) {}

  CannedResponse insert(CannedResponse row);

  CannedResponse update(CannedResponse row);

  Optional<CannedResponse> findById(UUID id);

  Optional<CannedResponse> findByShortcut(String shortcutKey);

  List<CannedResponse> list(ListFilter filter);

  long count(ListFilter filter);

  void recordUsage(UUID id, Instant usedAt);
}
