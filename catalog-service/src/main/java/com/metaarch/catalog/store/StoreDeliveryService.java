package com.metaarch.catalog.store;

import com.metaarch.catalog.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreDeliveryService {
  private static final String ACTIVE_STATUS = "Active";

  private final StoreRepository stores;
  private final StoreDeliveryZoneRepository zones;
  private final StoreDeliveryMethodRepository methods;
  private final OpenRouteServiceClient routes;
  public StoreDeliveryService(StoreRepository stores, StoreDeliveryZoneRepository zones, StoreDeliveryMethodRepository methods, OpenRouteServiceClient routes) {
    this.stores = stores; this.zones = zones; this.methods = methods; this.routes = routes;
  }

  @Transactional(readOnly = true)
  public List<StoreDeliveryZoneResponse> listZones(String storeId) {
    requireStore(storeId);
    return zones.findByStoreIdOrderByMinRadiusKmAsc(storeId).stream().map(zone -> StoreDeliveryZoneResponse.from(zone, methods.findByZoneIdOrderByDeliveryTypeAsc(zone.getId()))).toList();
  }

  @Transactional(readOnly = true)
  public DeliveryZoneLocationResponse getZoneLocation(Long deliveryZoneId) {
    StoreDeliveryZone zone = zones.findById(deliveryZoneId).orElseThrow(() -> new ResourceNotFoundException("Delivery zone not found: " + deliveryZoneId));
    return new DeliveryZoneLocationResponse(zone.getId(), zone.getStoreId(), resolveZoneLocation(zone));
  }

  @Transactional
  public StoreDeliveryZoneResponse createZone(String storeId, StoreDeliveryZoneRequest request) {
    requireStore(storeId);
    StoreDeliveryZone zone = new StoreDeliveryZone();
    zone.setStoreId(storeId);
    zone.setLocationId(resolveZoneLocation(zone));
    applyZone(zone, request);
    StoreDeliveryZone saved = zones.save(zone);
    saveMethods(saved, request.methods());
    return StoreDeliveryZoneResponse.from(saved, methods.findByZoneIdOrderByDeliveryTypeAsc(saved.getId()));
  }

  @Transactional
  public StoreDeliveryZoneResponse updateZone(String storeId, Long id, StoreDeliveryZoneRequest request) {
    requireStore(storeId);
    StoreDeliveryZone zone = zones.findById(id).filter(item -> item.getStoreId().equals(storeId)).orElseThrow(() -> new ResourceNotFoundException("Delivery zone not found: " + id));
    applyZone(zone, request);
    zone.setLocationId(resolveZoneLocation(zone));
    methods.deleteByZoneId(zone.getId());
    saveMethods(zone, request.methods());
    return StoreDeliveryZoneResponse.from(zone, methods.findByZoneIdOrderByDeliveryTypeAsc(zone.getId()));
  }

  @Transactional
  public void deleteZone(String storeId, Long id) {
    requireStore(storeId);
    StoreDeliveryZone zone = zones.findById(id).filter(item -> item.getStoreId().equals(storeId)).orElseThrow(() -> new ResourceNotFoundException("Delivery zone not found: " + id));
    methods.deleteByZoneId(zone.getId());
    zones.delete(zone);
  }

  @Transactional(readOnly = true)
  public DeliveryQuoteResponse quote(String storeId, BigDecimal customerLatitude, BigDecimal customerLongitude, BigDecimal orderTotal, String deliveryMethod) {
    Store store = requireStore(storeId);
    if ("global-store".equalsIgnoreCase(storeId) || "global".equalsIgnoreCase(deliveryMethod)) {
      return new DeliveryQuoteResponse(storeId, store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude, true, null, null, null, null, "India-wide", null, null, "global", BigDecimal.ZERO, null, false, null, null, null, false, List.of(), "Global Delivery - Tracking ID provided after dispatch");
    }
    if (customerLatitude == null || customerLongitude == null) {
      throw new OpenRouteServiceClient.RouteUnavailableException("Customer coordinates are required to verify delivery route.");
    }
    if (store.getLatitude() == null || store.getLongitude() == null) {
      boolean pickupAvailable = store.isPickupEnabled() && ACTIVE_STATUS.equalsIgnoreCase(store.getStatus());
      List<StoreDeliveryMethodResponse> pickupMethods = methods.findByStoreIdOrderByDeliveryTypeAsc(storeId).stream()
          .filter(StoreDeliveryMethod::isEnabled).filter(method -> isPickup(method.getDeliveryType()))
          .map(StoreDeliveryMethodResponse::from).toList();
      String message = pickupAvailable ? "Store location is not configured yet. Store pickup is available." : "Store location is not configured yet.";
      return new DeliveryQuoteResponse(storeId, null, null, customerLatitude, customerLongitude, false, null, null, null, null,
          null, null, null, normalizeMethod(deliveryMethod), BigDecimal.ZERO, null, false, null, null, null, pickupAvailable,
          pickupMethods, message);
    }
    OpenRouteServiceClient.RouteResult route = routes.drivingRoute(store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude);
    BigDecimal distance = route.distanceKm();
    BigDecimal radiusDistance = straightLineDistanceKm(store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude);
    boolean pickupAvailable = store.isPickupEnabled() && ACTIVE_STATUS.equalsIgnoreCase(store.getStatus());
    List<StoreDeliveryZone> storeZones = zones.findByStoreIdOrderByMinRadiusKmAsc(storeId);
    boolean hasActiveZones = storeZones.stream().anyMatch(item -> ACTIVE_STATUS.equalsIgnoreCase(item.getStatus()));
    if (!hasActiveZones && store.getDeliveryRadiusKm() != null && store.getDeliveryRadiusKm().signum() > 0
        && radiusDistance.compareTo(store.getDeliveryRadiusKm()) > 0) {
      return outsideDeliveryRadius(store, customerLatitude, customerLongitude, route, deliveryMethod, pickupAvailable);
    }
    StoreDeliveryZone zone = storeZones.stream()
      .filter(item -> ACTIVE_STATUS.equalsIgnoreCase(item.getStatus()))
      .filter(item -> radiusDistance.compareTo(item.getMinRadiusKm()) >= 0 && radiusDistance.compareTo(item.getMaxRadiusKm()) <= 0)
      .findFirst().orElse(null);
    if (zone == null && !hasActiveZones && store.getDeliveryRadiusKm() != null && store.getDeliveryRadiusKm().signum() > 0
        && radiusDistance.compareTo(store.getDeliveryRadiusKm()) <= 0 && store.isDeliveryEnabled() && ACTIVE_STATUS.equalsIgnoreCase(store.getStatus())) {
      return storeRadiusQuote(store, customerLatitude, customerLongitude, route, orderTotal, deliveryMethod, pickupAvailable);
    }
    if (zone == null || !store.isDeliveryEnabled() || !ACTIVE_STATUS.equalsIgnoreCase(store.getStatus())) {
      List<StoreDeliveryMethodResponse> pickupMethods = methods.findByStoreIdOrderByDeliveryTypeAsc(storeId).stream()
        .filter(StoreDeliveryMethod::isEnabled)
        .filter(method -> isPickup(method.getDeliveryType()))
        .map(StoreDeliveryMethodResponse::from)
        .toList();
      String message = pickupAvailable ? "Outside delivery area. Store pickup is available." : "Outside delivery area";
      return new DeliveryQuoteResponse(storeId, store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude, false, distance, route.distanceMeters(), route.durationSeconds(), route.durationMinutes(), null, route.geometry(), null, normalizeMethod(deliveryMethod), BigDecimal.ZERO, null, false, null, null, null, pickupAvailable, pickupMethods, message);
    }
    List<StoreDeliveryMethodResponse> availableMethods = methods.findByZoneIdOrderByDeliveryTypeAsc(zone.getId()).stream().filter(StoreDeliveryMethod::isEnabled).map(StoreDeliveryMethodResponse::from).toList();
    String selectedMethod = selectedMethod(deliveryMethod, availableMethods);
    StoreDeliveryMethodResponse selected = availableMethods.stream()
      .filter(method -> sameMethod(method.deliveryType(), selectedMethod))
      .findFirst()
      .orElse(null);
    if (orderTotal != null && zone.getMinimumOrder() != null && orderTotal.compareTo(zone.getMinimumOrder()) < 0) {
      String locationId = zone.getLocationId() == null || zone.getLocationId().isBlank()
          ? canonicalStoreLocation(store) : zone.getLocationId().trim();
      return new DeliveryQuoteResponse(storeId, store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude, false, distance, route.distanceMeters(), route.durationSeconds(), route.durationMinutes(), matchedZoneLabel(zone), route.geometry(), zone.getId(), selectedMethod, BigDecimal.ZERO, zone.getFreeDeliveryAbove(), false, zone.getMinimumOrder(), zone.getEstimatedDeliveryMin(), zone.getEstimatedDeliveryMax(), pickupAvailable, availableMethods, "Minimum order is " + zone.getMinimumOrder(), locationId);
    }
    BigDecimal charge = selected != null && selected.deliveryCharge() != null ? selected.deliveryCharge() : zone.getDeliveryCharge();
    boolean freeDeliveryApplied = orderTotal != null && zone.getFreeDeliveryAbove() != null && orderTotal.compareTo(zone.getFreeDeliveryAbove()) >= 0 && !isPickup(selectedMethod);
    if (freeDeliveryApplied || isPickup(selectedMethod)) charge = BigDecimal.ZERO;
    boolean methodAvailable = selected != null;
    String locationId = zone.getLocationId() == null || zone.getLocationId().isBlank()
        ? canonicalStoreLocation(store) : zone.getLocationId().trim();
    return new DeliveryQuoteResponse(storeId, store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude, methodAvailable, distance, route.distanceMeters(), route.durationSeconds(), route.durationMinutes(), matchedZoneLabel(zone), route.geometry(), zone.getId(), selectedMethod, charge, zone.getFreeDeliveryAbove(), freeDeliveryApplied, zone.getMinimumOrder(), zone.getEstimatedDeliveryMin(), zone.getEstimatedDeliveryMax(), pickupAvailable, availableMethods, methodAvailable ? "Delivery available" : "Selected delivery method is unavailable for this address", locationId);
  }

  private BigDecimal straightLineDistanceKm(BigDecimal storeLatitude, BigDecimal storeLongitude, BigDecimal customerLatitude, BigDecimal customerLongitude) {
    double lat1 = Math.toRadians(storeLatitude.doubleValue()), lat2 = Math.toRadians(customerLatitude.doubleValue());
    double dLat = lat2 - lat1, dLon = Math.toRadians(customerLongitude.doubleValue() - storeLongitude.doubleValue());
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
      + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return BigDecimal.valueOf(6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
  }

  private DeliveryQuoteResponse outsideDeliveryRadius(Store store, BigDecimal customerLatitude, BigDecimal customerLongitude,
      OpenRouteServiceClient.RouteResult route, String deliveryMethod, boolean pickupAvailable) {
    List<StoreDeliveryMethodResponse> pickupMethods = methods.findByStoreIdOrderByDeliveryTypeAsc(store.getId()).stream()
      .filter(StoreDeliveryMethod::isEnabled).filter(method -> isPickup(method.getDeliveryType()))
      .map(StoreDeliveryMethodResponse::from).toList();
    String message = pickupAvailable ? "Outside delivery area. Store pickup is available." : "Outside delivery area";
    return new DeliveryQuoteResponse(store.getId(), store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude,
      false, route.distanceKm(), route.distanceMeters(), route.durationSeconds(), route.durationMinutes(), null, route.geometry(),
      null, normalizeMethod(deliveryMethod), BigDecimal.ZERO, null, false, null, null, null, pickupAvailable, pickupMethods, message);
  }

  private DeliveryQuoteResponse storeRadiusQuote(Store store, BigDecimal customerLatitude, BigDecimal customerLongitude,
      OpenRouteServiceClient.RouteResult route, BigDecimal orderTotal, String requestedMethod, boolean pickupAvailable) {
    List<StoreDeliveryMethodResponse> available = java.util.Arrays.stream(store.getDeliveryModes() == null ? new String[0] : store.getDeliveryModes().split(","))
      .map(String::trim).filter(value -> !value.isBlank()).filter(value -> !isPickup(value))
      .map(value -> new StoreDeliveryMethodResponse(null, store.getId(), null, value, true, store.getDeliveryCharge(),
          store.getDeliveryEtaMinutes() == null ? null : store.getDeliveryEtaMinutes() + " min", null, null, null, null)).toList();
    String requested = requestedMethod == null || requestedMethod.isBlank() ? null : normalizeMethod(requestedMethod);
    StoreDeliveryMethodResponse method = available.stream().filter(item -> sameMethod(item.deliveryType(), requested)).findFirst()
      .orElseGet(() -> available.stream().findFirst().orElse(null));
    String selected = method == null ? null : method.deliveryType();
    BigDecimal charge = method == null || method.deliveryCharge() == null ? (store.getDeliveryCharge() == null ? BigDecimal.ZERO : store.getDeliveryCharge()) : method.deliveryCharge();
    BigDecimal freeAbove = store.getFreeDeliveryAbove();
    boolean free = orderTotal != null && freeAbove != null && orderTotal.compareTo(freeAbove) >= 0;
    if (free) charge = BigDecimal.ZERO;
    boolean availableForDelivery = method != null;
    String radius = store.getDeliveryRadiusKm().stripTrailingZeros().toPlainString();
    return new DeliveryQuoteResponse(store.getId(), store.getLatitude(), store.getLongitude(), customerLatitude, customerLongitude,
      availableForDelivery, route.distanceKm(), route.distanceMeters(), route.durationSeconds(), route.durationMinutes(),
      "Store radius " + radius + " KM", route.geometry(), null, selected, charge, freeAbove, free, null,
      store.getDeliveryEtaMinutes(), store.getDeliveryEtaMinutes(), pickupAvailable, available,
      availableForDelivery ? "Delivery available within store radius" : "No delivery method is configured for this store", canonicalStoreLocation(store));
  }

  @Transactional(readOnly = true)
  public DeliveryZoneLocationResponse getStoreLocation(String storeId) {
    Store store = requireStore(storeId);
    List<String> locations = zones.findByStoreIdOrderByMinRadiusKmAsc(storeId).stream().map(StoreDeliveryZone::getLocationId)
      .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    if (locations.isEmpty()) locations = zones.findMappedLocationIdsForStoreRegion(store.getLocation()).stream()
      .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    if (locations.size() == 1) return new DeliveryZoneLocationResponse(null, storeId, locations.getFirst());
    String locationId = locations.size() > 1 ? null : canonicalStoreLocation(store);
    return new DeliveryZoneLocationResponse(null, storeId, locationId);
  }

  private String resolveZoneLocation(StoreDeliveryZone zone) {
    if (zone.getLocationId() != null && !zone.getLocationId().isBlank()) return zone.getLocationId().trim();
    // Inherit IDs from persisted mappings, never from a numeric zone ID or a
    // customer-supplied label. A conflicting mapping requires explicit repair.
    List<String> sameStore = zones.findByStoreIdOrderByMinRadiusKmAsc(zone.getStoreId()).stream()
        .map(StoreDeliveryZone::getLocationId).filter(java.util.Objects::nonNull)
        .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    if (sameStore.size() == 1) return sameStore.getFirst();
    if (sameStore.size() > 1) throw new IllegalArgumentException("Store delivery location mappings conflict. Contact the store administrator.");
    Store store = requireStore(zone.getStoreId());
    List<String> region = store.getLocation() == null || store.getLocation().isBlank() ? List.of()
        : zones.findMappedLocationIdsForStoreRegion(store.getLocation().trim()).stream()
            .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    if (region.size() == 1) return region.getFirst();
    return canonicalStoreLocation(store);
  }

  private String canonicalStoreLocation(Store store) {
    for (String candidate : List.of(
        store.getLocation() == null ? "" : store.getLocation(),
        store.getArea() == null ? "" : store.getArea(),
        store.getCity() == null ? "" : store.getCity(),
        store.getId() == null ? "" : store.getId())) {
      if (!candidate.isBlank()) return candidate.trim();
    }
    throw new IllegalArgumentException("Set the store's service location before saving delivery radius rules.");
  }

  private Store requireStore(String id) {
    return stores.findById(id).orElseThrow(() -> new ResourceNotFoundException("Store not found: " + id));
  }

  private void applyZone(StoreDeliveryZone zone, StoreDeliveryZoneRequest request) {
    if (request.maxRadiusKm().compareTo(request.minRadiusKm()) <= 0) throw new IllegalArgumentException("Maximum distance must be greater than minimum distance");
    zone.setMinRadiusKm(request.minRadiusKm());
    zone.setMaxRadiusKm(request.maxRadiusKm());
    zone.setEstimatedDeliveryMin(request.estimatedDeliveryMin());
    zone.setEstimatedDeliveryMax(request.estimatedDeliveryMax());
    zone.setDeliveryCharge(request.deliveryCharge());
    zone.setFreeDeliveryAbove(request.freeDeliveryAbove());
    zone.setMinimumOrder(request.minimumOrder());
    zone.setStatus(request.status() == null || request.status().isBlank() ? ACTIVE_STATUS : request.status().trim());
  }

  private void saveMethods(StoreDeliveryZone zone, List<StoreDeliveryMethodRequest> requests) {
    if (requests == null) return;
    for (StoreDeliveryMethodRequest request : requests) {
      StoreDeliveryMethod method = new StoreDeliveryMethod();
      method.setStoreId(zone.getStoreId());
      method.setZone(zone);
      method.setDeliveryType(request.deliveryType().trim());
      method.setEnabled(request.enabled());
      method.setDeliveryCharge(request.deliveryCharge());
      method.setEstimatedTime(request.estimatedTime());
      method.setCutoffTime(request.cutoffTime());
      method.setSettingsConfiguration(request.settingsConfiguration());
      methods.save(method);
    }
  }

  private String selectedMethod(String requestedMethod, List<StoreDeliveryMethodResponse> availableMethods) {
    String normalized = normalizeMethod(requestedMethod);
    if (normalized != null && availableMethods.stream().anyMatch(method -> method.enabled() && sameMethod(method.deliveryType(), normalized))) return normalized;
    return availableMethods.stream().filter(StoreDeliveryMethodResponse::enabled).filter(method -> !isPickup(method.deliveryType()))
      .findFirst().map(StoreDeliveryMethodResponse::deliveryType).orElse(null);
  }

  private String normalizeMethod(String method) {
    if (method == null || method.isBlank()) return null;
    return switch (method.trim().toLowerCase()) {
      case "standard" -> "Standard Delivery";
      case "instant" -> "Instant Delivery";
      case "same-day", "sameday" -> "Same-Day Delivery";
      case "express" -> "Express Delivery";
      case "scheduled" -> "Scheduled Delivery";
      case "pickup", "store-pickup" -> "Store Pickup";
      default -> method.trim();
    };
  }

  private boolean sameMethod(String left, String right) {
    String normalizedLeft = normalizeMethod(left);
    String normalizedRight = normalizeMethod(right);
    return normalizedLeft != null && normalizedRight != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
  }

  private boolean isPickup(String method) {
    return sameMethod(method, "Store Pickup");
  }

  private String matchedZoneLabel(StoreDeliveryZone zone) {
    return zone.getMinRadiusKm().stripTrailingZeros().toPlainString() + "-" + zone.getMaxRadiusKm().stripTrailingZeros().toPlainString() + " KM";
  }

}
