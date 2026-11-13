package com.metaarch.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouteServiceClientTest {
  @Test
  void blankApiKeyUsesDeterministicFallbackRoute() {
    OpenRouteServiceClient client = new OpenRouteServiceClient(RestClient.builder(), " ");

    OpenRouteServiceClient.RouteResult route = client.drivingRoute(
      new BigDecimal("17.4446"),
      new BigDecimal("78.4483"),
      new BigDecimal("17.4410"),
      new BigDecimal("78.3917")
    );

    assertThat(client.configured()).isFalse();
    assertThat(route.distanceMeters()).isPositive();
    assertThat(route.durationSeconds()).isGreaterThanOrEqualTo(60);
    assertThat(route.distanceKm()).isPositive();
    assertThat(route.durationMinutes()).isPositive();
    assertThat(route.geometry()).isNull();
  }

  @Test
  void implausiblyLongRouteBetweenNearbyPointsFallsBackToStraightLineDistance() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
      .andRespond(withSuccess("""
        {"features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[]},
        "properties":{"summary":{"distance":8784990,"duration":36000}}}]}
        """, org.springframework.http.MediaType.APPLICATION_JSON));
    OpenRouteServiceClient client = new OpenRouteServiceClient(builder, "test-api-key");

    OpenRouteServiceClient.RouteResult route = client.drivingRoute(
      new BigDecimal("17.4435"), new BigDecimal("78.4681"),
      new BigDecimal("17.4410"), new BigDecimal("78.4483")
    );

    assertThat(route.distanceMeters()).isLessThan(10_000L);
    assertThat(route.geometry()).isNull();
    server.verify();
  }
}
