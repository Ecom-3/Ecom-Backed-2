package com.metaarch.order.order;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.metaarch.order.exception.DeliveryQuoteVerificationException;
import com.metaarch.order.exception.InvalidOrderStateException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
@Component
public class FulfillmentDeliveryClient {
  private final RestClient client;
  @Autowired
  public FulfillmentDeliveryClient(RestClient.Builder builder, @Value("${FULFILLMENT_SERVICE_URL:http://localhost:93315}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);factory.setReadTimeout(5000);
    client = builder.clone().baseUrl(url).requestFactory(factory).build();
  }
  FulfillmentDeliveryClient(RestClient client) { this.client = client; }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Fulfillment(UUID id, UUID orderId, UUID customerId, String storeId, String status) { }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Shipment(UUID id, UUID fulfillmentId, UUID orderId, String status, Instant deliveredAt) { }
  public record DeliveryEvidence(UUID orderId, UUID customerId, String storeId, UUID fulfillmentId, UUID shipmentId, Instant deliveredAt) { }
  public Optional<DeliveryEvidence> verify(UUID orderId, String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer ")) throw new InvalidOrderStateException("Sign in to verify delivery");
    try {
      var fulfillment = client.get().uri("/api/v1/fulfillments/order/{id}", orderId).header("Authorization", bearer).retrieve().body(Fulfillment.class);
      var shipment = client.get().uri("/api/v1/shipments/order/{id}", orderId).header("Authorization", bearer).retrieve().body(Shipment.class);
      if (fulfillment == null || shipment == null || fulfillment.id() == null || shipment.id() == null
          || !orderId.equals(fulfillment.orderId()) || !orderId.equals(shipment.orderId()) || !fulfillment.id().equals(shipment.fulfillmentId()))
        throw new InvalidOrderStateException("Delivery records do not match this order");
      if (!"DELIVERED".equals(fulfillment.status()) || !"DELIVERED".equals(shipment.status())) return Optional.empty();
      if (shipment.deliveredAt() == null) throw new InvalidOrderStateException("Delivery completion could not be verified");
      return Optional.of(new DeliveryEvidence(orderId, fulfillment.customerId(), fulfillment.storeId(), fulfillment.id(), shipment.id(), shipment.deliveredAt()));
    } catch (RestClientException error) {
      throw new DeliveryQuoteVerificationException("Delivery status could not be checked. Please retry.", error);
    }
  }
}
