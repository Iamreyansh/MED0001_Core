package com.nammamedmate.support.application;

import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.TicketCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/** Shared suggest / auto-assign ranking: specialty → lowest open_load → highest CSAT. */
final class AgentAssignment {

  private AgentAssignment() {}

  record Ranked(AgentProfile agent, int openLoad, double csat, boolean specialtyMatch) {}

  static List<Ranked> rankEligible(
      List<AgentProfile> online,
      TicketCategory category,
      ToIntFunction<AgentProfile> openLoadFn,
      ToDoubleFunction<AgentProfile> csatFn) {
    List<Ranked> eligible = new ArrayList<>();
    for (AgentProfile a : online) {
      int load = openLoadFn.applyAsInt(a);
      if (load >= a.maxLoad()) {
        continue;
      }
      eligible.add(new Ranked(a, load, csatFn.applyAsDouble(a), a.matchesSpecialty(category)));
    }
    eligible.sort(
        Comparator.comparing((Ranked r) -> !r.specialtyMatch())
            .thenComparingInt(Ranked::openLoad)
            .thenComparing(Comparator.comparingDouble(Ranked::csat).reversed()));
    return eligible;
  }
}
