package com.metaarch.order.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import com.metaarch.order.event.*;
import com.metaarch.order.exception.InvalidOrderStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class OrderServiceTest {
  @Mock(mockMaker = org.mockito.MockMakers.SUBCLASS) OrderRepository orders;
  @Mock OrderItemRepository items;
  @Mock OrderEventPublisher events;
  @Mock CatalogDeliveryQuoteClient catalogDeliveryQuoteClient;
  @Mock FulfillmentLifecycleVerifier fulfillmentLifecycle;
  @InjectMocks OrderService service;

  @BeforeEach void stubCatalogItems() {
    lenient().when(catalogDeliveryQuoteClient.verifyItem(anyString(), any(), anyBoolean(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
  }

  @Test
  void createCalculatesTotalsAndPublishesEvent() {
    when(orders.existsByOrderNumber(anyString())).thenReturn(false);
    when(orders.save(any(Order.class))).thenAnswer(invocation -> {
      Order order = invocation.getArgument(0);
      ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
      order.getItems().forEach(item -> ReflectionTestUtils.setField(item, "id", UUID.randomUUID()));
      order.getAddresses().forEach(address -> ReflectionTestUtils.setField(address, "id", UUID.randomUUID()));
      return order;
    });

    UUID sourceAddressId = UUID.randomUUID();
    CreateOrderRequest request = request(sourceAddressId.toString());
    when(catalogDeliveryQuoteClient.verify("store-1", new BigDecimal("17.4400000"), new BigDecimal("78.5000000"), new BigDecimal("39.98"), "Standard Delivery"))
      .thenReturn(new CatalogDeliveryQuoteClient.DeliveryQuote("store-1", new BigDecimal("17.4500000"), new BigDecimal("78.4900000"), new BigDecimal("17.4400000"), new BigDecimal("78.5000000"), true, new BigDecimal("4.25"), 4250L, 720, 12, "3-5 KM", null, 2L, "Standard Delivery", BigDecimal.ZERO, new BigDecimal("499.00"), false, new BigDecimal("100.00"), 20, 30, false, List.of(), "Delivery available", null));
    when(catalogDeliveryQuoteClient.resolveLocation(2L)).thenReturn(new CatalogDeliveryQuoteClient.DeliveryZoneLocation(2L, "store-1", "hyderabad"));

    OrderResponse response = service.create(request);

    assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
    assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(response.totalAmount()).isEqualByComparingTo("44.98");
    assertThat(response.handlingFee()).isEqualByComparingTo("5.00");
    assertThat(response.calculatedDistanceKm()).isEqualByComparingTo("4.25");
    assertThat(response.routeDistanceMeters()).isEqualTo(4250L);
    assertThat(response.matchedZoneLabel()).isEqualTo("3-5 KM");
    assertThat(response.items()).hasSize(1);
    assertThat(response.addresses()).singleElement().extracting(OrderAddressResponse::sourceAddressId).isEqualTo(sourceAddressId.toString());
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(events).publish(eq(OrderEventType.ORDER_CREATED), orderCaptor.capture());
    assertThat(orderCaptor.getValue().getLocationId()).isEqualTo("hyderabad");
  }

  @Test
  void globalOrderHasNoLocalStoreHandlingFee() {
    when(orders.existsByOrderNumber(anyString())).thenReturn(false);
    when(orders.save(any(Order.class))).thenAnswer(invocation -> {
      Order order = invocation.getArgument(0);
      ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
      order.getItems().forEach(item -> ReflectionTestUtils.setField(item, "id", UUID.randomUUID()));
      order.getAddresses().forEach(address -> ReflectionTestUtils.setField(address, "id", UUID.randomUUID()));
      return order;
    });
    CreateOrderRequest request = new CreateOrderRequest(
      UUID.randomUUID(), UUID.randomUUID(), "global-store",
      List.of(new CreateOrderItemRequest(UUID.randomUUID(), null, "Global spice", 1, new BigDecimal("219.00"))),
      List.of(new CreateOrderAddressRequest(AddressType.SHIPPING, "Line 1", null, "Hyderabad", "Telangana", "India", "500001", null, null)),
      null, null, PaymentStatus.PENDING, null, null, null, null, null, null, null, null, null, null,
      "global", "global", null, BigDecimal.ZERO, null, null, null, false, null, null, null, null, null);

    OrderResponse response = service.create(request);

    assertThat(response.handlingFee()).isEqualByComparingTo("0.00");
    assertThat(response.totalAmount()).isEqualByComparingTo("219.00");
  }

  @Test
  void createReverifiesRequestDeliverySnapshotWithCatalog() {
    when(orders.existsByOrderNumber(anyString())).thenReturn(false);
    when(orders.save(any(Order.class))).thenAnswer(invocation -> {
      Order order = invocation.getArgument(0);
      ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
      order.getItems().forEach(item -> ReflectionTestUtils.setField(item, "id", UUID.randomUUID()));
      order.getAddresses().forEach(address -> ReflectionTestUtils.setField(address, "id", UUID.randomUUID()));
      return order;
    });

    CreateOrderRequest request = requestWithDeliverySnapshot();
    when(catalogDeliveryQuoteClient.verify(eq(request.storeId()), any(), any(), any(), any())).thenReturn(snapshotQuote());
    when(catalogDeliveryQuoteClient.resolveLocation(1L)).thenReturn(new CatalogDeliveryQuoteClient.DeliveryZoneLocation(1L, "india-store-1-1", "hyderabad"));

    OrderResponse response = service.create(request);

    assertThat(response.totalAmount()).isEqualByComparingTo("59.98");
    assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(response.deliveryCharge()).isEqualByComparingTo("15.00");
    assertThat(response.calculatedDistanceKm()).isEqualByComparingTo("5.60");
    assertThat(response.routeDistanceMeters()).isEqualTo(5601L);
    assertThat(response.matchedZoneLabel()).isEqualTo("0-10 KM");
    verify(catalogDeliveryQuoteClient).verify(eq(request.storeId()), any(), any(), eq(new BigDecimal("39.98")), eq("Instant Delivery"));
    verify(events).publish(eq(OrderEventType.ORDER_CREATED), any(Order.class));
  }

  @Test
  void createFailsWhenDeliveryZoneLocationCannotBeResolved() {
    when(orders.existsByOrderNumber(anyString())).thenReturn(false);
    CreateOrderRequest request = requestWithDeliverySnapshot();
    when(catalogDeliveryQuoteClient.verify(eq(request.storeId()), any(), any(), any(), any())).thenReturn(snapshotQuote());
    when(catalogDeliveryQuoteClient.resolveLocation(1L))
      .thenThrow(new com.metaarch.order.exception.DeliveryQuoteVerificationException("Delivery zone has no authoritative location mapping: 1"));

    assertThatThrownBy(() -> service.create(request))
      .isInstanceOf(com.metaarch.order.exception.DeliveryQuoteVerificationException.class)
      .hasMessageContaining("no authoritative location mapping");
    verify(orders, never()).save(any(Order.class));
    verify(events, never()).publish(any(), any());
  }

  @Test
  void cancelRejectsShippedOrder() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-TEST");
    order.setStatus(OrderStatus.SHIPPED);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.cancel(orderId)).isInstanceOf(InvalidOrderStateException.class);
    verify(events, never()).publish(any(), any());
  }

  @Test
  void updateStatusRejectsDirectDelivery() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-TEST");
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.DELIVERED))).isInstanceOf(InvalidOrderStateException.class);
  }

  @Test
  void updateStatusRejectsShippingWithoutPersistedInTransitShipment() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-TEST");
    order.setStatus(OrderStatus.PROCESSING);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentIs(orderId, "IN_TRANSIT")).thenReturn(false);

    assertThatThrownBy(() -> service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.SHIPPED)))
      .isInstanceOf(InvalidOrderStateException.class)
      .hasMessage("An in-transit shipment is required before marking an order shipped");
    verify(events, never()).publish(any(), any());
  }

  @Test
  void updateStatusRejectsDeliveryWithoutPersistedDeliveredShipment() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-TEST");
    order.setStatus(OrderStatus.SHIPPED);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentIs(orderId, "DELIVERED")).thenReturn(false);

    assertThatThrownBy(() -> service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.DELIVERED)))
      .isInstanceOf(InvalidOrderStateException.class)
      .hasMessage("A delivered shipment is required before marking an order delivered");
    verify(events, never()).publish(any(), any());
  }

  @Test
  void updateStatusAllowsShippingWhenPersistedInTransitShipmentExists() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-SHIP-VERIFIED");
    order.setStatus(OrderStatus.PROCESSING);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentIs(orderId, "IN_TRANSIT")).thenReturn(true);

    service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.SHIPPED));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    verify(fulfillmentLifecycle).shipmentIs(orderId, "IN_TRANSIT");
    verify(events).publish(OrderEventType.ORDER_STATUS_UPDATED, order);
  }

  @Test
  void updateStatusAllowsDeliveryWhenPersistedDeliveredShipmentExists() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-DELIVER-VERIFIED");
    order.setStatus(OrderStatus.SHIPPED);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentIs(orderId, "DELIVERED")).thenReturn(true);

    service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.DELIVERED));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    verify(fulfillmentLifecycle).shipmentIs(orderId, "DELIVERED");
    verify(events).publish(OrderEventType.ORDER_STATUS_UPDATED, order);
  }

  @Test
  void updateStatusRejectsIdempotentShippedStatusWhenShipmentIsMissing() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-SHIP-HISTORICAL");
    order.setStatus(OrderStatus.SHIPPED);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentHasStatus(orderId, java.util.Set.of("IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"))).thenReturn(false);

    assertThatThrownBy(() -> service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.SHIPPED)))
      .isInstanceOf(InvalidOrderStateException.class)
      .hasMessage("A persisted shipment is required for an order marked shipped");
    verify(events, never()).publish(any(), any());
  }

  @Test
  void updateStatusRejectsIdempotentDeliveredStatusWhenShipmentIsMissing() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-DELIVER-HISTORICAL");
    order.setStatus(OrderStatus.DELIVERED);
    when(orders.findLockedById(orderId)).thenReturn(Optional.of(order));
    when(fulfillmentLifecycle.shipmentIs(orderId, "DELIVERED")).thenReturn(false);

    assertThatThrownBy(() -> service.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.DELIVERED)))
      .isInstanceOf(InvalidOrderStateException.class)
      .hasMessage("A delivered shipment is required before marking an order delivered");
    verify(events, never()).publish(any(), any());
  }

  @Test
  void paymentStatusRequestAcceptsPaymentServiceSuccessValue() {
    UpdateOrderPaymentStatusRequest request = new UpdateOrderPaymentStatusRequest("SUCCESS");

    assertThat(request.normalizedPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
  }

  @Test
  void publicPaymentStatusCannotFabricateConfirmation() {
    UUID orderId = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store-1", "ORD-TEST");
    order.setPaymentStatus(PaymentStatus.PAID);
    assertThatThrownBy(() -> service.updatePaymentStatus(orderId, PaymentStatus.SUCCESS))
      .isInstanceOf(InvalidOrderStateException.class);
    verify(events, never()).publish(any(), any());
  }

  private CreateOrderRequest request() {
    return request(null);
  }

  private CreateOrderRequest request(String sourceAddressId) {
    return new CreateOrderRequest(
      UUID.randomUUID(),
      UUID.randomUUID(),
      "store-1",
      List.of(new CreateOrderItemRequest(UUID.randomUUID(), null, "Keyboard", 2, new BigDecimal("19.99"))),
      List.of(new CreateOrderAddressRequest(AddressType.SHIPPING, "Line 1", null, "Hyderabad", "Telangana", "India", "500001", null, null, sourceAddressId)),
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      new BigDecimal("17.4400000"),
      new BigDecimal("78.5000000"),
      null,
      null,
      "Standard Delivery",
      null,
      null,
      null,
      null,
      null,
      null,
      false,
      null,
      null,
      null,
      null,
      null);
  }

  @Test void verifiedPaymentIsIdempotentAndCannotRegressToFailed() {
    UUID id = UUID.randomUUID();
    var order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store", "ORD-VERIFY");
    order.setTotalAmount(BigDecimal.TEN);
    when(orders.findLockedById(id)).thenReturn(Optional.of(order));
    var paid = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_SUCCESS, UUID.randomUUID(), id,
      order.getCustomerId(), "ref", BigDecimal.TEN, "INR", PaymentEventStatus.SUCCESS, java.time.Instant.now());
    service.applyVerifiedPayment(paid);
    service.applyVerifiedPayment(paid);
    verify(events, times(1)).publish(eq(OrderEventType.ORDER_STATUS_UPDATED), eq(order));
    var failed = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_FAILED, paid.paymentId(), id,
      order.getCustomerId(), "ref", BigDecimal.TEN, "INR", PaymentEventStatus.FAILED, java.time.Instant.now());
    assertThatThrownBy(() -> service.applyVerifiedPayment(failed)).isInstanceOf(InvalidOrderStateException.class);
    assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
  }

  @Test void wrongPaymentAmountCannotMarkOrderPaid() {
    UUID id = UUID.randomUUID();
    var order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store", "ORD-VERIFY-AMOUNT");
    order.setTotalAmount(BigDecimal.TEN);
    when(orders.findLockedById(id)).thenReturn(Optional.of(order));
    var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_SUCCESS, UUID.randomUUID(), id,
      order.getCustomerId(), "ref", BigDecimal.ONE, "INR", PaymentEventStatus.SUCCESS, java.time.Instant.now());
    assertThatThrownBy(() -> service.applyVerifiedPayment(event)).isInstanceOf(InvalidOrderStateException.class);
    assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    verifyNoInteractions(events);
  }

  @Test void duplicateOrderStatusPublishesOnce() {
    UUID id = UUID.randomUUID();
    var order = new Order(UUID.randomUUID(), UUID.randomUUID(), "store", "ORD-STATUS-RETRY");
    when(orders.findLockedById(id)).thenReturn(Optional.of(order));
    var request = new UpdateOrderStatusRequest(OrderStatus.CONFIRMED);
    service.updateStatus(id, request);
    service.updateStatus(id, request);
    verify(events, times(1)).publish(OrderEventType.ORDER_STATUS_UPDATED, order);
  }

  private CreateOrderRequest requestWithDeliverySnapshot() {
    return new CreateOrderRequest(
      UUID.randomUUID(),
      UUID.randomUUID(),
      "india-store-1-1",
      List.of(new CreateOrderItemRequest(UUID.randomUUID(), null, "Chicken Curry Cut", 1, new BigDecimal("39.98"))),
      List.of(new CreateOrderAddressRequest(AddressType.SHIPPING, "Line 1", null, "Hyderabad", "Telangana", "India", "500038", null, null)),
      null,
      null,
      PaymentStatus.PENDING,
      1L,
      new BigDecimal("5.60"),
      5601L,
      917,
      16,
      "0-10 KM",
      new BigDecimal("17.4427096"),
      new BigDecimal("78.4439616"),
      null,
      null,
      "Instant Delivery",
      null,
      null,
      new BigDecimal("60.00"),
      15,
      30,
      new BigDecimal("499.00"),
      false,
      null,
      null,
      "Pickup from SR Nagar Store",
      null,
      null);
  }

  private CatalogDeliveryQuoteClient.DeliveryQuote snapshotQuote() {
    return new CatalogDeliveryQuoteClient.DeliveryQuote("india-store-1-1", null, null, null, null, true, new BigDecimal("5.60"), 5601L, 917, 16, "0-10 KM", null, 1L, "Instant Delivery", new BigDecimal("15.00"), new BigDecimal("499.00"), false, null, 15, 30, false, List.of(), "Delivery available", null);
  }
}

