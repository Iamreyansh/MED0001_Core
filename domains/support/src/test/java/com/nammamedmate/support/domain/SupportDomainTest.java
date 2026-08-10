package com.nammamedmate.support.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportDomainTest {

  @Test
  void ticketIdsAndSlaMappings() {
    assertThat(TicketIds.format(TicketIds.dayKey(Instant.parse("2026-07-24T10:00:00Z")), 42))
        .isEqualTo("TKT-20260724-000042");
    assertThatThrownBy(() -> TicketIds.format(TicketIds.dayKey(Instant.now()), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TicketIds.format(TicketIds.dayKey(Instant.now()), 1_000_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TicketPriority.LOW.defaultSlaLevel()).isEqualTo(SlaLevel.L1);
    assertThat(TicketPriority.MEDIUM.defaultSlaLevel()).isEqualTo(SlaLevel.L2);
    assertThat(TicketPriority.HIGH.defaultSlaLevel()).isEqualTo(SlaLevel.L3);
    assertThat(TicketPriority.URGENT.defaultSlaLevel()).isEqualTo(SlaLevel.L4);
    assertThat(SlaLevel.L1.next()).isEqualTo(SlaLevel.L2);
    assertThat(SlaLevel.L2.next()).isEqualTo(SlaLevel.L3);
    assertThat(SlaLevel.L3.next()).isEqualTo(SlaLevel.L4);
    assertThat(SlaLevel.L4.next()).isEqualTo(SlaLevel.L4);
    assertThat(SlaLevel.L1.firstResponseWindow().toMinutes()).isEqualTo(30);
    assertThat(SlaLevel.L2.firstResponseWindow().toHours()).isEqualTo(2);
    assertThat(SlaLevel.L3.firstResponseWindow().toHours()).isEqualTo(8);
    assertThat(SlaLevel.L4.firstResponseWindow().toHours()).isEqualTo(24);
  }

  @Test
  void agentSpecialtyAndSlaBreach() {
    AgentProfile a =
        new AgentProfile(
            java.util.UUID.randomUUID(), List.of("ORDER"), true, 0, "A", Instant.now());
    assertThat(a.maxLoad()).isEqualTo(20);
    assertThat(a.matchesSpecialty(TicketCategory.ORDER)).isTrue();
    assertThat(a.matchesSpecialty(TicketCategory.PAYMENT)).isFalse();
    AgentProfile open =
        new AgentProfile(java.util.UUID.randomUUID(), List.of(), true, 5, "B", Instant.now());
    assertThat(open.matchesSpecialty(TicketCategory.RIDER)).isTrue();

    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    Ticket t =
        new Ticket(
            java.util.UUID.randomUUID(),
            "TKT-20260724-000001",
            java.util.UUID.randomUUID(),
            null,
            null,
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L3,
            now.minusSeconds(1),
            now.minusSeconds(1),
            now.minusSeconds(1),
            null,
            TicketChannel.APP,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    assertThat(t.slaBreached(now)).isTrue();
    assertThat(t.withStatus(TicketStatus.AWAITING_CUSTOMER, now).slaBreached(now)).isFalse();
    assertThat(
            t.withFirstResponse(now, TicketStatus.IN_PROGRESS, now).slaBreached(now.plusSeconds(1)))
        .isFalse();
    assertThat(SlaAdherence.pct(9, 10)).isEqualTo(90.0);
    assertThat(t.withSlaResume(java.time.Duration.ofMinutes(5), now).firstResponseDueAt())
        .isEqualTo(t.firstResponseDueAt().plus(java.time.Duration.ofMinutes(5)));
    assertThat(t.withL4Notified(now).slaL4NotifiedAt()).isEqualTo(now);
  }

  @Test
  void disputeIdsAndLiabilityMatrix() {
    assertThat(DisputeIds.format(DisputeIds.dayKey(Instant.parse("2026-07-24T10:00:00Z")), 14))
        .isEqualTo("DSP-20260724-000014");
    assertThatThrownBy(() -> DisputeIds.format(DisputeIds.dayKey(Instant.now()), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DisputeIds.format(DisputeIds.dayKey(Instant.now()), 1_000_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(LiabilityMatrix.recommend(DisputeType.WRONG_ITEMS)).isEqualTo(LiableParty.PHARMACY);
    assertThat(LiabilityMatrix.recommend(DisputeType.MISSING_ITEMS))
        .isEqualTo(LiableParty.PHARMACY);
    assertThat(LiabilityMatrix.recommend(DisputeType.DAMAGED)).isEqualTo(LiableParty.PHARMACY);
    assertThat(LiabilityMatrix.recommend(DisputeType.EXPIRED_MEDICINE))
        .isEqualTo(LiableParty.PHARMACY);
    assertThat(LiabilityMatrix.recommend(DisputeType.QUALITY)).isEqualTo(LiableParty.PHARMACY);
    assertThat(LiabilityMatrix.recommend(DisputeType.NOT_DELIVERED)).isEqualTo(LiableParty.RIDER);
    assertThat(LiabilityMatrix.recommend(DisputeType.OVERCHARGED)).isEqualTo(LiableParty.PLATFORM);
    for (DisputeType t : DisputeType.values()) {
      assertThat(LiabilityMatrix.rationale(t)).isNotBlank();
    }
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    Dispute open =
        new Dispute(
            java.util.UUID.randomUUID(),
            "DSP-20260724-000001",
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            DisputeType.WRONG_ITEMS,
            "d",
            List.of(),
            DisputeStatus.OPEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now.minusSeconds(1),
            LiableParty.PHARMACY,
            false,
            null,
            now,
            now,
            null);
    assertThat(open.slaBreached(now)).isTrue();
    assertThat(open.withRejected("no", "n", now, now).slaBreached(now)).isFalse();
    Dispute closed =
        new Dispute(
            java.util.UUID.randomUUID(),
            "DSP-20260724-000002",
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            DisputeType.WRONG_ITEMS,
            "d",
            null,
            DisputeStatus.CLOSED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now.minusSeconds(1),
            LiableParty.PHARMACY,
            false,
            null,
            now,
            now,
            null);
    assertThat(closed.slaBreached(now)).isFalse();
  }
}
