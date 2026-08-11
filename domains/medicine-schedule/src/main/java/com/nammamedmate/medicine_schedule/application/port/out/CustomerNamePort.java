package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Resolves the account holder's display name for SELF member auto-create. */
public interface CustomerNamePort {

  String nameFor(UUID customerId);
}
