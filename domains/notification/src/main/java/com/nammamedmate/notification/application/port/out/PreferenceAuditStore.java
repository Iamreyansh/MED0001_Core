package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.PreferenceAuditEntry;

public interface PreferenceAuditStore {

  void insert(PreferenceAuditEntry entry);
}
