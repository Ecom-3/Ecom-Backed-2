package com.metaarch.catalog.store;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.bind.annotation.ResponseStatus;

@Component
public class OpenRouteServiceClient {
  private final RestClient restClient;
  private final String apiKey;

  public OpenRouteServiceClient(RestClient.Builder builder, @Value("${openrouteservice.api-key:${openroute.service.api-key:${OPENROUTESERVICE_API_KEY:}}}") String apiKey) {
    this.restClient = builder.baseUrl("https://api.openrouteservice.org").build();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  public boolean configured() {
    return !apiKey.isBlank();
  }

  @SuppressWarnings("unchecked")
  public RouteResult drivingRoute(BigDecimal storeLatitude, BigDecimal storeLongitude, BigDecimal customerLatitude, BigDecimal customerLongitude) {
    if (!configured()) return fallbackRoute(storeLatitude, storeLongitude, customerLatitude, customerLongitude);
    Map<String, Object> body = Map.of(
      "coordinates", List.of(
        List.of(storeLongitude, storeLatitude),
        List.of(customerLongitude, customerLatitude)
      )
    );
    try {
      Map<String, Object> response = restClient.post()
        .uri("/v2/directions/driving-car/geojson")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", apiKey)
        .body(body)
        .retrieve()
        .body(Map.class);
      if (response == null) throw new RouteUnavailableException("Route response was not returned.");
      List<Map<String, Object>> features = (List<Map<String, Object>>) response.get("features");
      if (features == null || features.isEmpty()) throw new RouteUnavailableException("No drivable route found.");
      Map<String, Object> feature = features.get(0);
      Map<String, Object> properties = (Map<String, Object>) feature.get("properties");
      Map<String, Object> summary = properties == null ? null : (Map<String, Object>) properties.get("summary");
      if (summary == null) throw new RouteUnavailableException("Route summary was not returned.");
      Number distance = (Number) summary.get("distance");
      Number duration = (Number) summary.get("duration");
      if (distance == null || duration == null) throw new RouteUnavailableException("Route distance or duration was not returned.");
      double straightLineMeters = haversineKm(storeLatitude.doubleValue(), storeLongitude.doubleValue(),
        customerLatitude.doubleValue(), customerLongitude.doubleValue()) * 1000.0;
      // A routing provider can occasionally return a geographically impossible
      // route (for example, thousands of kilometres between nearby addresses).
      // Do not let that bad response reject an otherwise local delivery quote.
      if (distance.doubleValue() > Math.max(50_000.0, straightLineMeters * 8.0 + 10_000.0)) {
        return fallbackRoute(storeLatitude, storeLongitude, customerLatitude, customerLongitude);
      }
      return new RouteResult(distance.longValue(), duration.intValue(), feature.get("geometry"));
    } catch (RouteUnavailableException ex) {
      throw ex;
    } catch (Exception ex) {
      return fallbackRoute(storeLatitude, storeLongitude, customerLatitude, customerLongitude);
    }
  }

  private RouteResult fallbackRoute(BigDecimal storeLatitude, BigDecimal storeLongitude, BigDecimal customerLatitude, BigDecimal customerLongitude) {
    double distanceKm = haversineKm(storeLatitude.doubleValue(), storeLongitude.doubleValue(), customerLatitude.doubleValue(), customerLongitude.doubleValue());
    long distanceMeters = Math.max(1, Math.round(distanceKm * 1000));
    int durationSeconds = Math.max(60, (int) Math.round((distanceKm / 22.0) * 3600));
    return new RouteResult(distanceMeters, durationSeconds, null);
  }

  private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusKm = 6371.0088;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
      + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  public record RouteResult(Long distanceMeters, Integer durationSeconds, Object geometry) {
    public BigDecimal distanceKm() {
      return BigDecimal.valueOf(distanceMeters).divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
    }
    public Integer durationMinutes() {
      return Math.max(1, (int) Math.ceil(durationSeconds / 60.0));
    }
  }

  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public static class RouteUnavailableException extends RuntimeException {
    public RouteUnavailableException(String message) { super(message); }
    public RouteUnavailableException(String message, Throwable cause) { super(message, cause); }
  }
}
