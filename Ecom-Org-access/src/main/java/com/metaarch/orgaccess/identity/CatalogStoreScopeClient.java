package com.metaarch.orgaccess.identity;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Confirms a Location Admin assigns staff only to stores visible in its Catalog scope. */
@Component
public class CatalogStoreScopeClient {
  private final RestClient client;

  public CatalogStoreScopeClient(RestClient.Builder builder,
      @Value("${catalog.service.url:${CATALOG_SERVICE_URL:http://localhost:98089}}") String baseUrl) {
    client = builder.baseUrl(baseUrl).build();
  }

  public boolean storeBelongsToLocation(String storeId, String location) {
    if (storeId == null || storeId.isBlank() || location == null || location.isBlank()) return false;
    try {
      List<?> rows = client.get().uri("/api/v1/stores")
          .headers(headers -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) headers.setBearerAuth(jwt.getToken().getTokenValue());
          })
          .retrieve().body(List.class);
      if (rows == null) return false;
      return rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
          .anyMatch(row -> storeId.trim().equalsIgnoreCase(String.valueOf(row.get("id")))
              && location.trim().equalsIgnoreCase(String.valueOf(row.get("location"))));
    } catch (RestClientException error) {
      return false;
    }
  }
}
