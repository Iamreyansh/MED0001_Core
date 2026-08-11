package com.nammamedmate.notification.application.port.out;

import java.util.Optional;

/**
 * Optional EPIC-022 channel health lookup. No compile dep on integration — apps may bridge;
 * notification ships an always-healthy stub.
 */
public interface CommunicationChannelLookupPort {

  Optional<String> resolveActiveProvider(String channel);
}
