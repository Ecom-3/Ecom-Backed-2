package com.metaarch.catalog.store;

import java.math.BigDecimal;
import java.util.List;

public record DeliveryQuoteResponse(
  String storeId,
  BigDecimal storeLatitude,
  BigDecimal storeLongitude,
  BigDecimal customerLatitude,
  BigDecimal customerLongitude,
  boolean deliveryAvailable,
  BigDecimal distanceKm,
  Long routeDistanceMeters,
  Integer routeDurationSeconds,
  Integer routeDurationMinutes,
  String matchedZoneLabel,
  Object routeGeometry,
  Long deliveryZoneId,
  String selectedDeliveryMethod,
  BigDecimal deliveryCharge,
  BigDecimal freeDeliveryAbove,
  boolean freeDeliveryApplied,
  BigDecimal minimumOrder,
  Integer estimatedDeliveryMin,
  Integer estimatedDeliveryMax,
  boolean storePickupAvailable,
  List<StoreDeliveryMethodResponse> methods,
  String message,
  String locationId
) {
  public DeliveryQuoteResponse(String storeId, BigDecimal storeLatitude, BigDecimal storeLongitude,
      BigDecimal customerLatitude, BigDecimal customerLongitude, boolean deliveryAvailable, BigDecimal distanceKm,
      Long routeDistanceMeters, Integer routeDurationSeconds, Integer routeDurationMinutes, String matchedZoneLabel,
      Object routeGeometry, Long deliveryZoneId, String selectedDeliveryMethod, BigDecimal deliveryCharge,
      BigDecimal freeDeliveryAbove, boolean freeDeliveryApplied, BigDecimal minimumOrder, Integer estimatedDeliveryMin,
      Integer estimatedDeliveryMax, boolean storePickupAvailable, List<StoreDeliveryMethodResponse> methods, String message) {
    this(storeId, storeLatitude, storeLongitude, customerLatitude, customerLongitude, deliveryAvailable, distanceKm,
        routeDistanceMeters, routeDurationSeconds, routeDurationMinutes, matchedZoneLabel, routeGeometry, deliveryZoneId,
        selectedDeliveryMethod, deliveryCharge, freeDeliveryAbove, freeDeliveryApplied, minimumOrder,
        estimatedDeliveryMin, estimatedDeliveryMax, storePickupAvailable, methods, message, null);
  }
}
