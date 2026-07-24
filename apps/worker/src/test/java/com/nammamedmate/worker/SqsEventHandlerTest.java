package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqsEventHandlerTest {

  @Test
  void nullInputReturnsNullWithoutBootingPerRequest() {
    SqsEventHandler handler = new SqsEventHandler();
    assertThat(handler.handleRequest(null, null)).isNull();
  }

  @Test
  void iteratesRecordsSkippingNulls() {
    SQSEvent event = new SQSEvent();
    SQSMessage real = new SQSMessage();
    real.setBody("{\"type\":\"order.created\"}");
    List<SQSMessage> records = new ArrayList<>();
    records.add(null);
    records.add(real);
    event.setRecords(records);

    SqsEventHandler handler = new SqsEventHandler();

    assertThat(handler.handleRequest(event, null)).isNull();
  }
}
