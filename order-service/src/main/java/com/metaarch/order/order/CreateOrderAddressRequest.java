package com.metaarch.order.order;

import jakarta.validation.constraints.*;

public record CreateOrderAddressRequest(
  AddressType addressType,
  @NotBlank @Size(max = 255) String addressLine1,
  @Size(max = 255) String addressLine2,
  @NotBlank @Size(max = 120) String city,
  @NotBlank @Size(max = 120) String state,
  @NotBlank @Size(max = 120) String country,
  @NotBlank @Size(max = 30) String postalCode,
  @Size(max = 180) String recipientName,
  @Size(max = 30) String recipientPhone,
  @Size(max = 64) String sourceAddressId
) {
  public CreateOrderAddressRequest(AddressType addressType, String addressLine1, String addressLine2, String city, String state, String country, String postalCode, String recipientName, String recipientPhone) {
    this(addressType, addressLine1, addressLine2, city, state, country, postalCode, recipientName, recipientPhone, null);
  }
}
