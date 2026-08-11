package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailTemplate;
import java.util.List;
import java.util.Optional;

public interface EmailTemplateStore {

  Optional<EmailTemplate> findById(String templateId);

  boolean exists(String templateId);

  void upsert(EmailTemplate template);

  List<EmailTemplate> list(EmailCategory category, Boolean active);
}
