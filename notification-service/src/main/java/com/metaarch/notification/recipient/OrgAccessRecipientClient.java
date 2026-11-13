package com.metaarch.notification.recipient;

import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrgAccessRecipientClient {
  private static final Logger log = LoggerFactory.getLogger(OrgAccessRecipientClient.class);
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private final RestClient client;
  private final String token;

  public OrgAccessRecipientClient(RestClient.Builder builder,
      @Value("${org-access.service.url:${ORG_ACCESS_SERVICE_URL:http://localhost:93310}}") String baseUrl,
      @Value("${org-access.service.token:${ORG_ACCESS_TOKEN:}}") String token) {
    this.token = token == null ? "" : token.trim();
    this.client = builder.baseUrl(baseUrl.replaceAll("/+$", "")).build();
  }

  public List<String> findRecipients(String storeId, String locationId) {
    if (storeId == null || storeId.isBlank() || locationId == null || locationId.isBlank()) return List.of();
    try {
      RecipientResponse response = client.get()
        .uri(uri -> uri.path("/internal/notification-recipients")
          .queryParam("storeId", storeId.trim()).queryParam("locationId", locationId.trim()).build())
        .headers(headers -> { if (!token.isBlank()) headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token); })
        .retrieve().body(RecipientResponse.class);
      if (response == null || response.emails() == null) return List.of();
      return response.emails().stream().filter(Objects::nonNull).map(String::trim)
        .map(String::toLowerCase).filter(email -> EMAIL.matcher(email).matches()).distinct().toList();
    } catch (RuntimeException ex) {
      log.warn("Staff recipient lookup failed for storeId={}, locationId={}: {}", storeId, locationId, ex.getMessage());
      return List.of();
    }
  }

  public record RecipientResponse(List<String> emails) { }
}
