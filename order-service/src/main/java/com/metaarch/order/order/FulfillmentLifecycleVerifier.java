package com.metaarch.order.order;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FulfillmentLifecycleVerifier {
  private final RestClient client;
  private final ObjectMapper mapper;

  public FulfillmentLifecycleVerifier(@Value("${FULFILLMENT_SERVICE_URL:http://127.0.0.1:53315}") String baseUrl, ObjectMapper mapper) {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.mapper = mapper;
  }

  public boolean shipmentIs(UUID orderId, String expectedStatus) {
    return shipmentHasStatus(orderId, Set.of(expectedStatus));
  }

  public boolean shipmentHasStatus(UUID orderId, Set<String> expectedStatuses) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) {
      throw new IllegalStateException("Unable to verify shipment state.");
    }
    try {
      return client.get().uri("/api/v1/shipments/order/" + orderId)
        .header("Authorization", "Bearer " + token.getToken().getTokenValue())
        .exchange((request, response) -> {
          if (response.getStatusCode().value() == 404) return false;
          if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Unable to verify shipment state.");
          Map<?, ?> body = mapper.readValue(response.getBody(), Map.class);
          return expectedStatuses.contains(body.get("status"));
        });
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to verify shipment state.", ex);
    }
  }
}
