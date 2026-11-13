package com.metaarch.order.order;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GlobalStoreAssignmentInventoryClient {
  private final RestClient client;
  public GlobalStoreAssignmentInventoryClient(RestClient.Builder builder, @Value("${inventory.service.url:${INVENTORY_SERVICE_URL:http://localhost:98095}}") String url) {
    client = builder.baseUrl(url).requestInterceptor((request, body, execution) -> {
      var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
        request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
      return execution.execute(request, body);
    }).build();
  }
  public Inventory inventory(UUID productId, UUID variantId, String storeId) {
    Map<?,?> result = client.get().uri(uri -> uri.path("/api/v1/inventories").queryParam("productId", productId)
        .queryParamIfPresent("variantId", java.util.Optional.ofNullable(variantId)).queryParam("storeId", storeId).build())
        .retrieve().body(Map.class);
    if (result == null) return null;
    return new Inventory(String.valueOf(result.get("storeId")), String.valueOf(result.get("status")),
        Integer.parseInt(String.valueOf(result.get("availableQuantity"))), Integer.parseInt(String.valueOf(result.get("reservedQuantity"))));
  }
  public record Inventory(String storeId, String status, int availableQuantity, int reservedQuantity) { }
}
