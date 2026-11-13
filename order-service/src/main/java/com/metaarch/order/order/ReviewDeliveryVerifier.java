package com.metaarch.order.order;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ReviewDeliveryVerifier {
  private final RestClient client;
  private final ObjectMapper mapper;

  public ReviewDeliveryVerifier(@Value("${FULFILLMENT_SERVICE_URL:http://127.0.0.1:53315}") String baseUrl, ObjectMapper mapper) {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.mapper = mapper;
  }

  public boolean isDelivered(UUID orderId) {
    if ("DELIVERED".equals(currentStatus("/api/v1/fulfillments/order/" + orderId))) return true;
    return "DELIVERED".equals(currentStatus("/api/v1/shipments/order/" + orderId));
  }

  private String currentStatus(String path) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) return "";
    try {
      return client.get().uri(path).header("Authorization", "Bearer " + token.getToken().getTokenValue())
        .exchange((request, response) -> {
          if (response.getStatusCode().value() == 404) return "";
          if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Unable to verify delivery status.");
          Map<?, ?> body = mapper.readValue(response.getBody(), Map.class);
          Object status = body.get("status");
          return status == null ? "" : status.toString();
        });
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to verify delivery status.", ex);
    }
  }
}
