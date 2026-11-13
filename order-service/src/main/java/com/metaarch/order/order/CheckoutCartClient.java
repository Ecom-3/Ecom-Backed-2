package com.metaarch.order.order;

import com.metaarch.order.exception.DeliveryQuoteVerificationException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class CheckoutCartClient {
  private final RestClient http;
  private final String baseUrl;
  public CheckoutCartClient(RestClient.Builder builder, @Value("${cart.service.url:${CART_SERVICE_URL:http://localhost:98091}}") String url) {
    var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    http=builder.requestFactory(factory).build(); baseUrl=url.replaceAll("/+$", "");
  }
  public void claim(CreateOrderRequest request, String hash) {
    var authentication=SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token) || !com.metaarch.sync.PublicApiSecurity.owns(request.customerId())) {
      throw new AccessDeniedException("Verified customer authentication required");
    }
    try {
      Snapshot snapshot=http.post().uri(baseUrl+"/api/v1/carts/"+request.cartId()+"/checkout-claim")
        .headers(headers -> headers.setBearerAuth(token.getToken().getTokenValue()))
        .body(new Claim(hash, request.storeId(), request.items().stream().map(i -> new Line(i.productId(), i.variantId(), i.quantity())).toList()))
        .retrieve().body(Snapshot.class);
      if (snapshot==null || !request.cartId().equals(snapshot.id()) || !request.customerId().equals(snapshot.customerId())
          || !request.storeId().equals(snapshot.storeId()) || !"CHECKED_OUT".equals(snapshot.status())) {
        throw new AccessDeniedException("Cart ownership could not be verified");
      }
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value()==401 || ex.getStatusCode().value()==403 || ex.getStatusCode().value()==404) throw new AccessDeniedException("Cart ownership could not be verified");
      if (ex.getStatusCode().value()==409) throw new com.metaarch.order.exception.ConflictException("Cart changed or was already checked out");
      throw new DeliveryQuoteVerificationException("Cart verification failed", ex);
    } catch (ResourceAccessException | HttpServerErrorException ex) {
      throw new DeliveryQuoteVerificationException("Cart Service is unavailable; retry the same checkout", ex);
    }
  }
  record Line(UUID productId, UUID variantId, Integer quantity) { }
  record Claim(String requestHash, String storeId, List<Line> items) { }
  record Snapshot(UUID id, UUID customerId, String storeId, String status) { }
}
