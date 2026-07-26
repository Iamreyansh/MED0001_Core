package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;

public class FakeReferralStore implements ReferralStore {

  private final Map<UUID, ReferralRecord> byCustomer = new ConcurrentHashMap<>();
  private final Map<String, UUID> codeToCustomer = new ConcurrentHashMap<>();
  private final List<ReferralEventRecord> events = new ArrayList<>();
  public boolean clearLocks;
  public boolean failNextInsertEvent;
  public boolean failNextInsertReferral;
  public ReferralRecord revealAfterFailedInsert;

  @Override
  public Optional<ReferralRecord> findByCustomerId(UUID customerId) {
    return Optional.ofNullable(byCustomer.get(customerId));
  }

  @Override
  public Optional<ReferralRecord> findByCode(String referralCode) {
    UUID customerId = codeToCustomer.get(referralCode);
    return customerId == null ? Optional.empty() : findByCustomerId(customerId);
  }

  @Override
  public Optional<ReferralRecord> lockByCustomerId(UUID customerId) {
    if (clearLocks) {
      return Optional.empty();
    }
    return findByCustomerId(customerId);
  }

  @Override
  public ReferralRecord insert(ReferralRecord record) {
    if (failNextInsertReferral) {
      failNextInsertReferral = false;
      if (revealAfterFailedInsert != null) {
        byCustomer.put(revealAfterFailedInsert.customerId(), revealAfterFailedInsert);
        codeToCustomer.put(
            revealAfterFailedInsert.referralCode(), revealAfterFailedInsert.customerId());
        revealAfterFailedInsert = null;
      }
      throw new DuplicateKeyException("duplicate referral");
    }
    if (byCustomer.containsKey(record.customerId())
        || codeToCustomer.containsKey(record.referralCode())) {
      throw new DuplicateKeyException("duplicate referral");
    }
    byCustomer.put(record.customerId(), record);
    codeToCustomer.put(record.referralCode(), record.customerId());
    return record;
  }

  @Override
  public ReferralRecord update(ReferralRecord record) {
    byCustomer.put(record.customerId(), record);
    return record;
  }

  @Override
  public boolean codeExists(String referralCode) {
    return codeToCustomer.containsKey(referralCode);
  }

  @Override
  public ReferralEventRecord insertEvent(ReferralEventRecord event) {
    if (failNextInsertEvent) {
      failNextInsertEvent = false;
      throw new DuplicateKeyException("duplicate referee event");
    }
    if (events.stream().anyMatch(e -> e.refereeCustomerId().equals(event.refereeCustomerId()))) {
      throw new DuplicateKeyException("duplicate referee event");
    }
    events.add(event);
    return event;
  }

  @Override
  public Optional<ReferralEventRecord> findEventByReferee(UUID refereeCustomerId) {
    return events.stream().filter(e -> e.refereeCustomerId().equals(refereeCustomerId)).findFirst();
  }

  @Override
  public Optional<ReferralEventRecord> lockEventById(UUID eventId) {
    return events.stream().filter(e -> e.id().equals(eventId)).findFirst();
  }

  @Override
  public ReferralEventRecord updateEvent(ReferralEventRecord event) {
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).id().equals(event.id())) {
        events.set(i, event);
        return event;
      }
    }
    events.add(event);
    return event;
  }

  @Override
  public long countEventsByReferrerAndStatus(UUID referrerCustomerId, ReferralEventStatus status) {
    return events.stream()
        .filter(e -> e.referrerCustomerId().equals(referrerCustomerId))
        .filter(e -> e.status() == status)
        .count();
  }

  public List<ReferralEventRecord> allEvents() {
    return List.copyOf(events);
  }
}
