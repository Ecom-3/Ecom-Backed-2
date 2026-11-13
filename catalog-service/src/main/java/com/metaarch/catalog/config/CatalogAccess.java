package com.metaarch.catalog.config;

import com.metaarch.catalog.banner.Banner;
import com.metaarch.catalog.banner.BannerRepository;
import com.metaarch.catalog.coupon.Coupon;
import com.metaarch.catalog.coupon.CouponRepository;
import com.metaarch.catalog.store.Store;
import com.metaarch.catalog.store.StoreRepository;
import com.metaarch.catalog.product.ProductRepository;
import com.metaarch.catalog.product.Product;
import com.metaarch.catalog.variant.ProductVariantRepository;
import com.metaarch.catalog.image.ProductImageRepository;
import com.metaarch.catalog.storeproduct.StoreProductRepository;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Enforces catalog write permissions against persisted location/store ownership. */
@Component("catalogAccess")
public class CatalogAccess {
  private static final Set<String> GLOBAL = Set.of("ROLE_ADMIN", "ROLE_GLOBAL_ADMIN", "ROLE_SUPER_ADMIN");
  private final StoreRepository stores;
  private final com.metaarch.catalog.store.StoreDeliveryZoneRepository zones;
  private final BannerRepository banners;
  private final CouponRepository coupons;
  private final ProductRepository products;
  private final ProductVariantRepository variants;
  private final ProductImageRepository images;
  private final StoreProductRepository storeProducts;

  public CatalogAccess(StoreRepository stores, com.metaarch.catalog.store.StoreDeliveryZoneRepository zones, BannerRepository banners, CouponRepository coupons,
      ProductRepository products, ProductVariantRepository variants, ProductImageRepository images, StoreProductRepository storeProducts) {
    this.stores = stores;
    this.zones = zones;
    this.banners = banners;
    this.coupons = coupons;
    this.products = products;
    this.variants = variants;
    this.images = images;
    this.storeProducts = storeProducts;
  }

  public boolean global(Authentication auth) { return hasAny(auth, GLOBAL); }
  public boolean superAdmin(Authentication auth) { return has(auth, "ROLE_SUPER_ADMIN"); }
  public boolean globalAdmin(Authentication auth) { return has(auth, "ROLE_GLOBAL_ADMIN"); }
  public boolean locationAdmin(Authentication auth) { return has(auth, "ROLE_LOCATION_ADMIN"); }
  public boolean storeManager(Authentication auth) { return has(auth, "ROLE_STORE_MANAGER"); }

  public boolean canManageProduct(Authentication auth, UUID productId) {
    if (globalAdmin(auth)) return productId != null && products.findById(productId).map(Product::isGlobalProduct).orElse(false);
    if (global(auth)) return true;
    if (storeManager(auth) && productId != null) {
      return products.findById(productId).map(product -> {
        String assignedStore = assignedStoreId(auth);
        return assignedStore != null && stores.existsById(assignedStore)
            && (assignedStore.equals(product.getOwnerStoreId())
                || storeProducts.findByProductId(productId).stream().anyMatch(mapping -> assignedStore.equals(mapping.getStoreId())));
      }).orElse(false);
    }
    return locationAdmin(auth) && productId != null
        && products.findById(productId).map(product -> inLocation(auth, product.getOwnerLocation())).orElse(false);
  }

  public boolean canCreateProduct(Authentication auth, com.metaarch.catalog.product.ProductRequest request) {
    return !globalAdmin(auth) || request != null && Boolean.TRUE.equals(request.globalProduct());
  }

  public boolean canUpdateProduct(Authentication auth, UUID productId, com.metaarch.catalog.product.ProductRequest request) {
    return canManageProduct(auth, productId) && (!globalAdmin(auth) || request != null && Boolean.TRUE.equals(request.globalProduct()));
  }

  public String assignedStoreId(Authentication auth) {
    if (!storeManager(auth) || auth == null || !auth.isAuthenticated()) return null;
    List<String> scopes = auth.getAuthorities().stream().map(a -> a.getAuthority())
        .filter(a -> a.startsWith("SCOPE_STORE:")).map(a -> a.substring("SCOPE_STORE:".length()).trim())
        .filter(value -> !value.isBlank()).distinct().toList();
    return scopes.size() == 1 ? scopes.get(0) : null;
  }

  public boolean canReadProduct(Authentication auth, UUID productId) {
    if (auth == null || !auth.isAuthenticated() || has(auth, "ROLE_ANONYMOUS")) return true;
    if (globalAdmin(auth)) return productId != null && products.findById(productId).map(Product::isGlobalProduct).orElse(false);
    if (global(auth)) return true;
    if (productId == null) return false;
    if (storeManager(auth)) return storeProducts.findByProductId(productId).stream().anyMatch(mapping -> canManageStore(auth, mapping.getStoreId()));
    if (!locationAdmin(auth)) return true;
    return products.findById(productId).map(product -> product.isGlobalProduct() || inLocation(auth, product.getOwnerLocation())
        || storeProducts.findByProductId(productId).stream().anyMatch(mapping -> canManageStore(auth, mapping.getStoreId()))).orElse(false);
  }

  public boolean canManageVariant(Authentication auth, UUID variantId) {
    if (globalAdmin(auth)) return variantId != null && variants.findById(variantId).map(variant -> variant.getProduct().isGlobalProduct()).orElse(false);
    if (global(auth)) return true;
    return (locationAdmin(auth) || storeManager(auth)) && variantId != null
        && variants.findById(variantId).map(variant -> canManageProduct(auth, variant.getProduct().getId())).orElse(false);
  }

  public boolean canManageImage(Authentication auth, UUID imageId) {
    if (globalAdmin(auth)) return imageId != null && images.findById(imageId).map(image -> image.getProduct().isGlobalProduct()).orElse(false);
    if (global(auth)) return true;
    return (locationAdmin(auth) || storeManager(auth)) && imageId != null
        && images.findById(imageId).map(image -> canManageProduct(auth, image.getProduct().getId())).orElse(false);
  }

  public boolean canReadVariant(Authentication auth, UUID variantId) {
    return auth == null || !auth.isAuthenticated() || has(auth, "ROLE_ANONYMOUS") || variantId != null
        && variants.findById(variantId).map(variant -> canReadProduct(auth, variant.getProduct().getId())).orElse(false);
  }

  public boolean canReadImage(Authentication auth, UUID imageId) {
    return auth == null || !auth.isAuthenticated() || has(auth, "ROLE_ANONYMOUS") || imageId != null
        && images.findById(imageId).map(image -> canReadProduct(auth, image.getProduct().getId())).orElse(false);
  }

  public boolean canManageStore(Authentication auth, String storeId) {
    if (global(auth)) return true;
    if (storeId == null || storeId.isBlank()) return false;
    if (storeManager(auth) && has(auth, "SCOPE_STORE:" + storeId.trim())) return true;
    return locationAdmin(auth) && stores.findById(storeId.trim()).map(store -> inLocation(auth, store.getLocation())).orElse(false);
  }

  public boolean canCreateStore(Authentication auth, String location) {
    return global(auth) || (locationAdmin(auth) && inLocation(auth, location));
  }

  public String authorizedStoreLocation(Authentication auth, String requestedLocation) {
    if (global(auth)) return requestedLocation;
    String assignedLocation = location(auth);
    return locationAdmin(auth) && sameLocation(assignedLocation, requestedLocation) ? assignedLocation : null;
  }

  public boolean canUpdateStore(Authentication auth, String storeId, String requestedLocation) {
    if (global(auth)) return true;
    if (storeId == null || storeId.isBlank() || requestedLocation == null || requestedLocation.isBlank()) return false;
    return stores.findById(storeId.trim()).map(store -> {
      String persistedLocation = store.getLocation();
      if (locationAdmin(auth)) return inLocation(auth, persistedLocation) && inLocation(auth, requestedLocation);
      return storeManager(auth)
          && has(auth, "SCOPE_STORE:" + storeId.trim())
          && persistedLocation != null
          && persistedLocation.trim().equalsIgnoreCase(requestedLocation.trim());
    }).orElse(false);
  }

  public List<Store> visibleStores(Authentication auth, List<Store> rows) {
    if (global(auth) || auth == null || !auth.isAuthenticated()) return rows;
    if (locationAdmin(auth)) return rows.stream().filter(s -> inLocation(auth, s.getLocation())).toList();
    if (storeManager(auth)) return rows.stream().filter(s -> has(auth, "SCOPE_STORE:" + s.getId())).toList();
    return rows;
  }

  public void requireStore(Authentication auth, String storeId) {
    if (!canManageStore(auth, storeId)) throw new AccessDeniedException("Store is outside your assigned scope.");
  }

  public boolean canReadStore(Authentication auth, String storeId) {
    return auth == null || !auth.isAuthenticated() || has(auth, "ROLE_ANONYMOUS") || canManageStore(auth, storeId)
        || hasAny(auth, Set.of("ROLE_CUSTOMER", "ROLE_DELIVERY_PARTNER"));
  }

  public boolean canManageLocation(Authentication auth, String locationId) {
    return global(auth) || (locationAdmin(auth) && inLocation(auth, locationId));
  }

  public boolean canReadDeliveryZone(Authentication auth, Long zoneId) {
    if (global(auth) || auth == null || !auth.isAuthenticated()) return true;
    return zoneId != null && zones.findById(zoneId).map(zone -> canReadStore(auth, zone.getStoreId())).orElse(false);
  }

  public boolean canCreateBanner(Authentication auth, com.metaarch.catalog.banner.BannerRequest request) {
    if (globalAdmin(auth)) return request.scopeOrDefault() == com.metaarch.catalog.banner.BannerScope.GLOBAL;
    if (global(auth)) return true;
    if (storeManager(auth)) {
      String storeId = assignedStoreId(auth);
      return storeId != null && request.scopeOrDefault() == com.metaarch.catalog.banner.BannerScope.STORE
          && storeId.equals(request.storeId())
          && stores.findById(storeId).map(store -> sameText(store.getLocation(), request.locationId())).orElse(false);
    }
    if (!locationAdmin(auth) || request.scopeOrDefault() == com.metaarch.catalog.banner.BannerScope.GLOBAL) return false;
    if (request.scopeOrDefault() == com.metaarch.catalog.banner.BannerScope.LOCATION)
      return canManageLocation(auth, request.locationId());
    return request.storeId() != null && canManageStore(auth, request.storeId())
        && (request.locationId() == null || canManageLocation(auth, request.locationId()));
  }

  public boolean canManageBanner(Authentication auth, java.util.UUID id) {
    if (globalAdmin(auth)) return id != null && banners.findById(id).map(b -> b.getScope() == com.metaarch.catalog.banner.BannerScope.GLOBAL).orElse(false);
    if (global(auth)) return true;
    return id != null && banners.findById(id).map(banner -> ownsBanner(auth, banner)).orElse(false);
  }

  public com.metaarch.catalog.banner.BannerRequest scopeBannerRequest(Authentication auth,
      com.metaarch.catalog.banner.BannerRequest request) {
    if (!storeManager(auth)) return request;
    String storeId = assignedStoreId(auth);
    Store store = storeId == null ? null : stores.findById(storeId).orElse(null);
    if (store == null || (request.scope() != null && request.scope() != com.metaarch.catalog.banner.BannerScope.STORE)
        || (request.storeId() != null && !request.storeId().isBlank() && !sameText(request.storeId(), storeId))
        || (request.locationId() != null && !request.locationId().isBlank()
            && !sameText(request.locationId(), store.getLocation()))) {
      throw new AccessDeniedException("Store Managers may manage banners only for their assigned store.");
    }
    return new com.metaarch.catalog.banner.BannerRequest(request.title(), request.description(), request.imageUrl(),
        request.mobileImageUrl(), request.targetUrl(), com.metaarch.catalog.banner.BannerScope.STORE,
        store.getLocation(), storeId, request.sortOrder(), request.status(), request.startsOn(), request.endsOn());
  }

  public List<Banner> visibleBanners(Authentication auth, List<Banner> rows) {
    if (globalAdmin(auth)) return rows.stream().filter(b -> b.getScope() == com.metaarch.catalog.banner.BannerScope.GLOBAL).toList();
    if (global(auth) || auth == null || !auth.isAuthenticated()) return rows;
    return rows.stream().filter(b -> ownsBanner(auth, b)).toList();
  }

  public boolean canCreateCoupon(Authentication auth, com.metaarch.catalog.coupon.CouponRequest request) {
    if (globalAdmin(auth)) return "GLOBAL".equalsIgnoreCase(request.scope());
    if (global(auth)) return true;
    if (storeManager(auth)) {
      String storeId = assignedStoreId(auth);
      return storeId != null && "STORE".equalsIgnoreCase(request.scope())
          && storeId.equals(request.store())
          && stores.findById(storeId).map(store -> sameText(store.getLocation(), request.location())).orElse(false);
    }
    if (!locationAdmin(auth) || !canManageLocation(auth, request.location())) return false;
    if ("LOCATION".equalsIgnoreCase(request.scope())) return request.store() == null || request.store().isBlank();
    return "STORE".equalsIgnoreCase(request.scope()) && request.store() != null
        && canManageStore(auth, request.store());
  }

  public boolean canManageCoupon(Authentication auth, java.util.UUID id) {
    if (globalAdmin(auth)) return id != null && coupons.findById(id).map(c -> "GLOBAL".equalsIgnoreCase(c.getScope())).orElse(false);
    if (global(auth)) return true;
    return id != null && coupons.findById(id).map(coupon -> ownsCoupon(auth, coupon)).orElse(false);
  }

  public com.metaarch.catalog.coupon.CouponRequest scopeCouponRequest(Authentication auth,
      com.metaarch.catalog.coupon.CouponRequest request) {
    if (!storeManager(auth)) return request;
    String storeId = assignedStoreId(auth);
    Store store = storeId == null ? null : stores.findById(storeId).orElse(null);
    if (store == null || (request.scope() != null && !"STORE".equalsIgnoreCase(request.scope()))
        || (request.store() != null && !request.store().isBlank() && !sameText(request.store(), storeId))
        || (request.location() != null && !request.location().isBlank()
            && !sameText(request.location(), store.getLocation()))) {
      throw new AccessDeniedException("Store Managers may manage coupons only for their assigned store.");
    }
    return new com.metaarch.catalog.coupon.CouponRequest(request.code(), request.couponName(), request.description(),
        request.discountType(), request.discount(), request.minimumOrder(), request.maximumDiscount(),
        request.startDate(), request.expiry(), request.usageLimit(), request.perUserLimit(), "STORE",
        store.getLocation(), storeId, request.product(), request.category(), request.eligibility(), request.status());
  }

  public List<Coupon> visibleCoupons(Authentication auth, List<Coupon> rows) {
    if (globalAdmin(auth)) return rows.stream().filter(c -> "GLOBAL".equalsIgnoreCase(c.getScope())).toList();
    if (global(auth) || auth == null || !auth.isAuthenticated()) return rows;
    return rows.stream().filter(c -> ownsCoupon(auth, c)).toList();
  }

  public String location(Authentication auth) {
    if (auth == null) return null;
    return auth.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("SCOPE_LOCATION:"))
        .map(a -> a.substring("SCOPE_LOCATION:".length()).trim()).filter(v -> !v.isBlank()).findFirst().orElse(null);
  }

  public boolean inLocation(Authentication auth, String location) {
    String scope = location(auth);
    return sameLocation(scope, location);
  }

  private boolean ownsBanner(Authentication auth, Banner banner) {
    if (storeManager(auth)) {
      String storeId = assignedStoreId(auth);
      return storeId != null && banner.getScope() == com.metaarch.catalog.banner.BannerScope.STORE
          && storeId.equals(banner.getStoreId())
          && stores.findById(storeId).map(store -> sameText(store.getLocation(), banner.getLocationId())).orElse(false);
    }
    if (!locationAdmin(auth)) return false;
    if (banner.getScope() == com.metaarch.catalog.banner.BannerScope.LOCATION)
      return inLocation(auth, banner.getLocationId());
    return banner.getScope() == com.metaarch.catalog.banner.BannerScope.STORE
        && canManageStore(auth, banner.getStoreId()) && (banner.getLocationId() == null || inLocation(auth, banner.getLocationId()));
  }

  private boolean ownsCoupon(Authentication auth, Coupon coupon) {
    if (storeManager(auth)) {
      String storeId = assignedStoreId(auth);
      return storeId != null && "STORE".equalsIgnoreCase(coupon.getScope())
          && storeId.equals(coupon.getStore())
          && stores.findById(storeId).map(store -> sameText(store.getLocation(), coupon.getLocation())).orElse(false);
    }
    if (!locationAdmin(auth) || !inLocation(auth, coupon.getLocation())) return false;
    if ("LOCATION".equalsIgnoreCase(coupon.getScope())) return coupon.getStore() == null || coupon.getStore().isBlank();
    return "STORE".equalsIgnoreCase(coupon.getScope()) && coupon.getStore() != null
        && canManageStore(auth, coupon.getStore());
  }

  private boolean has(Authentication auth, String authority) {
    return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
  }

  private boolean hasAny(Authentication auth, Set<String> authorities) {
    return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> authorities.contains(a.getAuthority()));
  }

  private boolean sameText(String left, String right) {
    return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
  }

  private boolean sameLocation(String left, String right) {
    String leftKey = locationKey(left);
    return leftKey != null && leftKey.equals(locationKey(right));
  }

  private String locationKey(String value) {
    if (value == null || value.isBlank()) return null;
    String key = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (key.startsWith("location-")) key = key.substring("location-".length());
    return key.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
  }
}
