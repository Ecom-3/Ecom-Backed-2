package com.metaarch.catalog.store;

import com.metaarch.catalog.common.AuditedEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stores", uniqueConstraints = @UniqueConstraint(name = "uk_stores_name", columnNames = "name"))
public class Store extends AuditedEntity {
  @Id @Column(length = 100) private String id;
  @Column(nullable = false, length = 180) private String name;
  @Column(name = "store_code", length = 60) private String storeCode;
  @Column(name = "logo_url", length = 2048) private String logoUrl;
  @Column(length = 1000) private String description;
  @Column(nullable = false, length = 160) private String location;
  @Column(nullable = false, length = 700) private String address;
  @Column(name = "door_number", length = 80) private String doorNumber;
  @Column(name = "street_name", length = 180) private String streetName;
  @Column(length = 160) private String area;
  @Column(length = 180) private String landmark;
  @Column(length = 120) private String city;
  @Column(length = 120) private String district;
  @Column(length = 120) private String state;
  @Column(length = 20) private String pincode;
  @Column(precision = 10, scale = 7) private BigDecimal latitude;
  @Column(precision = 10, scale = 7) private BigDecimal longitude;
  @Column(nullable = false, length = 40) private String provider = "OPENSTREETMAP";
  @Column(name = "provider_place_id", length = 120) private String providerPlaceId;
  @Column(name = "provider_osm_type", length = 20) private String providerOsmType;
  @Column(name = "provider_osm_id", length = 40) private String providerOsmId;
  @Column(name = "location_accuracy_meters", precision = 10, scale = 2) private BigDecimal locationAccuracyMeters;
  @Column(length = 30) private String phone;
  @Column(name = "alternate_phone", length = 30) private String alternatePhone;
  @Column(length = 180) private String email;
  @Column(name = "admin_name", length = 180) private String adminName;
  @Column(name = "store_rating", precision = 3, scale = 2) private BigDecimal storeRating;
  @Column(name = "gst_number", length = 30) private String gstNumber;
  @Column(length = 40) private String status = "Active";
  @Column(name = "delivery_enabled", nullable = false) private boolean deliveryEnabled = true;
  @Column(name = "pickup_enabled", nullable = false) private boolean pickupEnabled = true;
  @Column(name = "delivery_radius_km", precision = 8, scale = 2) private BigDecimal deliveryRadiusKm;
  @Column(name = "delivery_eta_minutes") private Integer deliveryEtaMinutes;
  @Column(name = "delivery_charge", precision = 10, scale = 2) private BigDecimal deliveryCharge;
  @Column(name = "free_delivery_above", precision = 10, scale = 2) private BigDecimal freeDeliveryAbove;
  @Column(name = "delivery_modes", length = 300) private String deliveryModes;
  @Column(name = "delivery_notes", length = 500) private String deliveryNotes;
  @Column(name = "is_new", nullable = false) private boolean newStore;
  @Column(nullable = false) private boolean active = true;

  protected Store() {}
  public Store(String id) { this.id = id; }
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getStoreCode() { return storeCode; }
  public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
  public String getLogoUrl() { return logoUrl; }
  public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getLocation() { return location; }
  public void setLocation(String location) { this.location = location; }
  public String getAddress() { return address; }
  public void setAddress(String address) { this.address = address; }
  public String getDoorNumber() { return doorNumber; }
  public void setDoorNumber(String doorNumber) { this.doorNumber = doorNumber; }
  public String getStreetName() { return streetName; }
  public void setStreetName(String streetName) { this.streetName = streetName; }
  public String getArea() { return area; }
  public void setArea(String area) { this.area = area; }
  public String getLandmark() { return landmark; }
  public void setLandmark(String landmark) { this.landmark = landmark; }
  public String getCity() { return city; }
  public void setCity(String city) { this.city = city; }
  public String getDistrict() { return district; }
  public void setDistrict(String district) { this.district = district; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public String getPincode() { return pincode; }
  public void setPincode(String pincode) { this.pincode = pincode; }
  public BigDecimal getLatitude() { return latitude; }
  public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
  public BigDecimal getLongitude() { return longitude; }
  public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getProviderPlaceId() { return providerPlaceId; }
  public void setProviderPlaceId(String providerPlaceId) { this.providerPlaceId = providerPlaceId; }
  public String getProviderOsmType() { return providerOsmType; }
  public void setProviderOsmType(String providerOsmType) { this.providerOsmType = providerOsmType; }
  public String getProviderOsmId() { return providerOsmId; }
  public void setProviderOsmId(String providerOsmId) { this.providerOsmId = providerOsmId; }
  public BigDecimal getLocationAccuracyMeters() { return locationAccuracyMeters; }
  public void setLocationAccuracyMeters(BigDecimal locationAccuracyMeters) { this.locationAccuracyMeters = locationAccuracyMeters; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getAlternatePhone() { return alternatePhone; }
  public void setAlternatePhone(String alternatePhone) { this.alternatePhone = alternatePhone; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getAdminName() { return adminName; }
  public void setAdminName(String adminName) { this.adminName = adminName; }
  public BigDecimal getStoreRating() { return storeRating; }
  public void setStoreRating(BigDecimal storeRating) { this.storeRating = storeRating; }
  public String getGstNumber() { return gstNumber; }
  public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public boolean isDeliveryEnabled() { return deliveryEnabled; }
  public void setDeliveryEnabled(boolean deliveryEnabled) { this.deliveryEnabled = deliveryEnabled; }
  public boolean isPickupEnabled() { return pickupEnabled; }
  public void setPickupEnabled(boolean pickupEnabled) { this.pickupEnabled = pickupEnabled; }
  public BigDecimal getDeliveryRadiusKm() { return deliveryRadiusKm; }
  public void setDeliveryRadiusKm(BigDecimal deliveryRadiusKm) { this.deliveryRadiusKm = deliveryRadiusKm; }
  public Integer getDeliveryEtaMinutes() { return deliveryEtaMinutes; }
  public void setDeliveryEtaMinutes(Integer deliveryEtaMinutes) { this.deliveryEtaMinutes = deliveryEtaMinutes; }
  public BigDecimal getDeliveryCharge() { return deliveryCharge; }
  public void setDeliveryCharge(BigDecimal deliveryCharge) { this.deliveryCharge = deliveryCharge; }
  public BigDecimal getFreeDeliveryAbove() { return freeDeliveryAbove; }
  public void setFreeDeliveryAbove(BigDecimal freeDeliveryAbove) { this.freeDeliveryAbove = freeDeliveryAbove; }
  public String getDeliveryModes() { return deliveryModes; }
  public void setDeliveryModes(String deliveryModes) { this.deliveryModes = deliveryModes; }
  public String getDeliveryNotes() { return deliveryNotes; }
  public void setDeliveryNotes(String deliveryNotes) { this.deliveryNotes = deliveryNotes; }
  public boolean isNew() { return newStore; }
  public void setNew(boolean newStore) { this.newStore = newStore; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }
}
