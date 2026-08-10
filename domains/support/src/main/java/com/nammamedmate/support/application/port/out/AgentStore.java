package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.AgentPerformanceSnapshot;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.TicketCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentStore {

  Optional<AgentProfile> findById(UUID adminUserId);

  /** All agent profiles (roster source). */
  List<AgentProfile> listAll();

  /** Online agents (any specialty). */
  List<AgentProfile> listOnline();

  /** Online agents matching specialty, ordered by ascending open load (caller filters cap). */
  List<AgentProfile> listOnlineForCategory(TicketCategory category);

  AgentProfile updateOnline(UUID adminUserId, boolean online, Instant updatedAt);

  Optional<String> findEmail(UUID adminUserId);

  void upsertSnapshot(AgentPerformanceSnapshot snapshot);

  List<AgentPerformanceSnapshot> listSnapshots(UUID agentId);

  Optional<AgentPerformanceSnapshot> findSnapshot(UUID agentId, LocalDate weekStart);
}
