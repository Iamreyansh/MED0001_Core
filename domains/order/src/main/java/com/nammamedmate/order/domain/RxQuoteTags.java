package com.nammamedmate.order.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Assigns FASTEST / LOWEST_PRICE among selectable (non-expired) quotes. */
public final class RxQuoteTags {

  public static final String FASTEST = "FASTEST";
  public static final String LOWEST_PRICE = "LOWEST_PRICE";

  public record TaggedQuote(
      UUID pharmacyId, int etaMinutes, long totalPayablePaise, boolean expired) {}

  private RxQuoteTags() {}

  public static Map<UUID, List<String>> assign(List<TaggedQuote> quotes) {
    Map<UUID, List<String>> out = new HashMap<>();
    List<TaggedQuote> live = new ArrayList<>();
    for (TaggedQuote q : quotes) {
      out.put(q.pharmacyId(), new ArrayList<>());
      if (!q.expired()) {
        live.add(q);
      }
    }
    if (live.isEmpty()) {
      return out;
    }
    TaggedQuote fastest =
        live.stream()
            .min(
                Comparator.comparingInt(TaggedQuote::etaMinutes)
                    .thenComparingLong(TaggedQuote::totalPayablePaise))
            .orElseThrow();
    TaggedQuote cheapest =
        live.stream()
            .min(
                Comparator.comparingLong(TaggedQuote::totalPayablePaise)
                    .thenComparingInt(TaggedQuote::etaMinutes))
            .orElseThrow();
    out.get(fastest.pharmacyId()).add(FASTEST);
    out.get(cheapest.pharmacyId()).add(LOWEST_PRICE);
    return out;
  }

  public static boolean canViewQuotes(
      int quotesReceived, java.time.Instant broadcastAt, java.time.Instant now) {
    if (quotesReceived >= 2) {
      return true;
    }
    if (quotesReceived >= 1 && broadcastAt != null && now != null) {
      return !now.isBefore(broadcastAt.plusSeconds(5 * 60L));
    }
    return false;
  }
}
