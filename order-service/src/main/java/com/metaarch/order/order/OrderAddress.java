package com.metaarch.order.order;

import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_addresses")
public class OrderAddress {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private Order order;
  @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "address_type", nullable = false, length = 30) private AddressType addressType;
  @Column(name = "address_line1", nullable = false, length = 255) private String addressLine1;
  @Column(name = "address_line2", length = 255) private String addressLine2;
  @Column(nullable = false, length = 120) private String city;
  @Column(nullable = false, length = 120) private String state;
  @Column(nullable = false, length = 120) private String country;
  @Column(name = "postal_code", nullable = false, length = 30) private String postalCode;
  @Column(name = "recipient_name", length = 180) private String recipientName;
  @Column(name = "recipient_phone", length = 30) private String recipientPhone;
  @Column(name = "source_address_id", length = 64) private String sourceAddressId;

  protected OrderAddress() { }

  public OrderAddress(AddressType addressType, String addressLine1, String addressLine2, String city, String state, String country, String postalCode, String recipientName, String recipientPhone) {
    this(addressType, addressLine1, addressLine2, city, state, country, postalCode, recipientName, recipientPhone, null);
  }

  public OrderAddress(AddressType addressType, String addressLine1, String addressLine2, String city, String state, String country, String postalCode, String recipientName, String recipientPhone, String sourceAddressId) {
    this.addressType = addressType;
    this.addressLine1 = addressLine1;
    this.addressLine2 = addressLine2;
    this.city = city;
    this.state = state;
    this.country = country;
    this.postalCode = postalCode;
    this.recipientName = recipientName;
    this.recipientPhone = recipientPhone;
    this.sourceAddressId = sourceAddressId;
  }

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public void setOrder(Order order) { this.order = order; }
  public UUID getOrderId() { return order == null ? null : order.getId(); }
  public AddressType getAddressType() { return addressType; }
  public String getAddressLine1() { return addressLine1; }
  public String getAddressLine2() { return addressLine2; }
  public String getCity() { return city; }
  public String getState() { return state; }
  public String getCountry() { return country; }
  public String getPostalCode() { return postalCode; }
  public String getRecipientName() { return recipientName; }
  public String getRecipientPhone() { return recipientPhone; }
  public String getSourceAddressId() { return sourceAddressId; }
}
