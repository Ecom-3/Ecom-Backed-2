package com.metaarch.order.order;

import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GlobalStoreAssignmentCatalogClient {
  private final RestClient client;
  public GlobalStoreAssignmentCatalogClient(RestClient.Builder builder, @Value("${catalog.service.url:${CATALOG_SERVICE_URL:http://localhost:98089}}") String url) {
    client = builder.baseUrl(url).requestInterceptor((request, body, execution) -> {
      var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
        request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
      return execution.execute(request, body);
    }).build();
  }
  public Store store(String storeId) {
    Map<?,?> result = client.get().uri("/api/v1/stores/{id}", storeId).retrieve().body(Map.class);
    if (result == null) return null;
    return new Store(String.valueOf(result.get("id")), Boolean.TRUE.equals(result.get("active")), String.valueOf(result.get("status")));
  }
  public List<Store> activeStores() {
    List<Map<String,Object>> rows = client.get().uri("/api/v1/stores?active=true").retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {});
    if (rows == null) return List.of();
    return rows.stream().map(row -> new Store(String.valueOf(row.get("id")), Boolean.TRUE.equals(row.get("active")), String.valueOf(row.get("status"))))
        .filter(store -> store.active() && "Active".equalsIgnoreCase(store.status())).toList();
  }
  public record Store(String id, boolean active, String status) { }
}
