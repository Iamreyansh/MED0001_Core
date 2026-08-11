package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.util.List;
import java.util.Optional;

public interface SmsTemplateStore {

  Optional<SmsTemplate> findById(String templateId);

  boolean exists(String templateId);

  void insert(SmsTemplate template);

  List<SmsTemplate> list(SmsCategory category, Boolean active);
}
