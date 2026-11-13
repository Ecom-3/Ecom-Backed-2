package com.metaarch.order.order;

import java.util.UUID;

public record OrderAddressResponse(UUID id, UUID orderId, AddressType addressType, String addressLine1, String addressLine2, String city, String state, String country, String postalCode, String recipientName, String recipientPhone, String sourceAddressId) {
  static OrderAddressResponse from(OrderAddress address) {
    return new OrderAddressResponse(address.getId(), address.getOrderId(), address.getAddressType(), address.getAddressLine1(), address.getAddressLine2(), address.getCity(), address.getState(), address.getCountry(), address.getPostalCode(), address.getRecipientName(), address.getRecipientPhone(), address.getSourceAddressId());
  }
}
