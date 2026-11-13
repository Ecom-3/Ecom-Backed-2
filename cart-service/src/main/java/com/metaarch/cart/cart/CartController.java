package com.metaarch.cart.cart;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
public class CartController {
  private final CartService service;
  private final CartOperations operations;
  public CartController(CartService service, CartOperations operations) { this.service = service; this.operations = operations; }
  @PreAuthorize("@cartAccess.customer(#request.customerId())")
  @PostMapping public ResponseEntity<CartResponse> create(@Valid @RequestBody CartRequest request) { CartResponse cart = service.create(request); return ResponseEntity.created(URI.create("/api/v1/carts/" + cart.id())).body(cart); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @GetMapping("/{cartId}") public CartResponse get(@PathVariable UUID cartId) { return service.get(cartId); }
  @PreAuthorize("@cartAccess.customer(#customerId)")
  @GetMapping("/active") public CartResponse getActive(@RequestParam UUID customerId, @RequestParam String storeId) { return service.getActive(customerId, storeId); }
  @PreAuthorize("@cartAccess.customer(#customerId)")
  @GetMapping("/customer/{customerId}/active") public java.util.List<CartResponse> getActiveCarts(@PathVariable UUID customerId) { return service.getActiveCarts(customerId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PostMapping("/{cartId}/items") public CartResponse addItem(@PathVariable UUID cartId, @RequestHeader(value="Idempotency-Key", required=false) String key, @Valid @RequestBody AddCartItemRequest request) { return operations.add(cartId, key, request); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PostMapping("/{cartId}/checkout-claim") public CartResponse claim(@PathVariable UUID cartId, @Valid @RequestBody CheckoutCartRequest request) { return operations.claim(cartId, request); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PatchMapping("/{cartId}/items/{itemId}") public CartResponse updateQuantity(@PathVariable UUID cartId, @PathVariable UUID itemId, @Valid @RequestBody UpdateCartItemQuantityRequest request) { return service.updateQuantity(cartId, itemId, request); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @DeleteMapping("/{cartId}/items/{itemId}") public CartResponse removeItem(@PathVariable UUID cartId, @PathVariable UUID itemId) { return service.removeItem(cartId, itemId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @DeleteMapping("/{cartId}/items") public CartResponse clear(@PathVariable UUID cartId) { return service.clear(cartId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PostMapping("/{cartId}/calculate-total") public CartResponse calculateTotal(@PathVariable UUID cartId) { return service.calculateTotal(cartId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PatchMapping("/{cartId}/items/{itemId}/save-for-later") public CartResponse saveForLater(@PathVariable UUID cartId, @PathVariable UUID itemId) { return service.saveForLater(cartId, itemId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @PatchMapping("/{cartId}/items/{itemId}/move-to-cart") public CartResponse moveToCart(@PathVariable UUID cartId, @PathVariable UUID itemId) { return service.moveToCart(cartId, itemId); }
  @PreAuthorize("@cartAccess.owner(#cartId)")
  @DeleteMapping("/{cartId}/items/{itemId}/saved-for-later") public CartResponse removeSavedForLater(@PathVariable UUID cartId, @PathVariable UUID itemId) { return service.removeSavedForLater(cartId, itemId); }
}
