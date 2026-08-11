package com.nammamedmate.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SqsNoOpHandlerTest {

  @Test
  void handlesNullBlankAndPayloadWithoutHandler() {
    @SuppressWarnings("unchecked")
    ObjectProvider<CustomerNotificationRequestedHandler> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    SqsNoOpHandler handler = new SqsNoOpHandler(provider);
    handler.handle(null);
    handler.handle("  ");
    handler.handle("{\"type\":\"order.created\"}");
  }

  @Test
  void delegatesToNotificationHandlerWhenPresent() {
    CustomerNotificationRequestedHandler inApp = mock(CustomerNotificationRequestedHandler.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<CustomerNotificationRequestedHandler> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(inApp);
    SqsNoOpHandler handler = new SqsNoOpHandler(provider);
    handler.handle("{\"type\":\"customer.notification.requested\"}");
    verify(inApp).handleMessage("{\"type\":\"customer.notification.requested\"}");
    handler.handle(null);
    verifyNoInteractions(mock(CustomerNotificationRequestedHandler.class));
  }
}
