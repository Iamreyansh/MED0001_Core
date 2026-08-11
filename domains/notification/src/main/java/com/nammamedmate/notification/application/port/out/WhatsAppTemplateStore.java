package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WhatsAppTemplateStore {

  Optional<WhatsAppTemplate> findByName(String templateName);

  boolean exists(String templateName);

  void insert(WhatsAppTemplate template);

  void touchLastUsed(String templateName, Instant at);

  List<WhatsAppTemplate> list(WhatsAppCategory category, WhatsAppTemplateStatus status);
}
