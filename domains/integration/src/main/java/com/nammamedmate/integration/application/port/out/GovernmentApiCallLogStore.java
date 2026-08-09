package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.GovernmentApiCallLog;

public interface GovernmentApiCallLogStore {

  void insert(GovernmentApiCallLog log);
}
