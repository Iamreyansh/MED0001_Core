package com.nammamedmate.support.application;

import com.nammamedmate.support.application.port.out.CannedResponseStore;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.domain.CannedResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared empty fakes for TicketService constructor after STORY-005. */
final class KbTestDoubles {

  private KbTestDoubles() {}

  static final class EmptyCanned implements CannedResponseStore {
    @Override
    public CannedResponse insert(CannedResponse row) {
      return row;
    }

    @Override
    public CannedResponse update(CannedResponse row) {
      return row;
    }

    @Override
    public Optional<CannedResponse> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public Optional<CannedResponse> findByShortcut(String shortcutKey) {
      return Optional.empty();
    }

    @Override
    public List<CannedResponse> list(ListFilter filter) {
      return List.of();
    }

    @Override
    public long count(ListFilter filter) {
      return 0;
    }

    @Override
    public void recordUsage(UUID id, Instant usedAt) {}
  }

  static final class EmptyOrders implements OrderContextPort {
    @Override
    public Optional<OrderContext> find(UUID orderId) {
      return Optional.empty();
    }
  }
}
