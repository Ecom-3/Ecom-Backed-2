package com.metaarch.cart.cart;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:cart-operations.sql"})
class CartConcurrencyTest {
  @Autowired CartService service;
  @Autowired CartOperations operations;
  @Autowired JdbcTemplate jdbc;
  @Autowired CartRepository carts;
  private CartResponse cart() { return service.create(new CartRequest(UUID.randomUUID(), "test-store")); }
  private AddCartItemRequest item(UUID product, int quantity) { return new AddCartItemRequest(product,null,"Test",quantity,new BigDecimal("10.00")); }

  @Test void concurrentUniqueAndDuplicateAdditionsHaveOneLineAndExactTotal() throws Exception {
    var cart=cart(); UUID product=UUID.randomUUID(); String duplicate=UUID.randomUUID().toString();
    try (var executor=Executors.newFixedThreadPool(6)) {
      List<Future<CartResponse>> results=new ArrayList<>();
      for (int i=0;i<12;i++) {
        String key=i<6 ? duplicate : UUID.randomUUID().toString();
        results.add(executor.submit(() -> operations.add(cart.id(),key,item(product,1))));
      }
      for (var result:results) result.get(30,TimeUnit.SECONDS);
    }
    var result=service.get(cart.id());
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).quantity()).isEqualTo(7);
    assertThat(result.totalAmount()).isEqualByComparingTo("70.00");
    assertThatThrownBy(() -> operations.add(cart.id(),duplicate,item(product,2))).isInstanceOf(com.metaarch.cart.exception.ConflictException.class);
    // Reconstruct the operations facade: replay depends on durable rows, not memory.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_mutation_requests WHERE cart_id=? AND request_key=?",Integer.class,cart.id().toString(),duplicate)).isEqualTo(1);
    assertThat(operations.add(cart.id(),duplicate,item(product,1)).totalAmount()).isEqualByComparingTo("70.00");
  }

  @Test void concurrentQuantityAndRemovalRemainConsistentAndRemovalIsRepeatable() throws Exception {
    var cart=cart(); var added=operations.add(cart.id(),UUID.randomUUID().toString(),item(UUID.randomUUID(),1));
    UUID id=added.items().get(0).id();
    try (var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(() -> service.updateQuantity(cart.id(),id,new UpdateCartItemQuantityRequest(3)));
      var second=executor.submit(() -> service.updateQuantity(cart.id(),id,new UpdateCartItemQuantityRequest(5)));
      first.get();second.get();
    }
    var result=service.get(cart.id());
    assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.TEN.multiply(BigDecimal.valueOf(result.items().get(0).quantity())));
    try (var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(() -> service.removeItem(cart.id(),id));
      var second=executor.submit(() -> service.removeItem(cart.id(),id));
      first.get();second.get();
    }
    assertThat(service.get(cart.id()).items()).isEmpty();
    assertThat(service.get(cart.id()).totalAmount()).isEqualByComparingTo("0");
  }

  @Test void savedItemDeletionRemovesOnlySavedLineAndKeepsActiveLines() {
    var cart=cart();
    var added=operations.add(cart.id(),UUID.randomUUID().toString(),item(UUID.randomUUID(),1));
    var saved=service.saveForLater(cart.id(),added.items().getFirst().id());
    var active=operations.add(cart.id(),UUID.randomUUID().toString(),item(UUID.randomUUID(),2));
    UUID savedId=saved.savedForLaterItems().getFirst().id();
    var removed=service.removeSavedForLater(cart.id(),savedId);
    assertThat(removed.savedForLaterItems()).isEmpty();
    assertThat(removed.items()).hasSize(1);
    assertThat(removed.items().getFirst().id()).isEqualTo(active.items().getFirst().id());
    assertThat(removed.totalAmount()).isEqualByComparingTo("20.00");
    assertThatThrownBy(() -> service.removeSavedForLater(cart.id(),active.items().getFirst().id()))
      .isInstanceOf(com.metaarch.cart.exception.ResourceNotFoundException.class);
  }

  @Test void claimReplayAllowsUpdatedCheckoutDetailsOnlyWhenCartSnapshotIsUnchanged() {
    var cart=cart(); UUID product=UUID.randomUUID();operations.add(cart.id(),UUID.randomUUID().toString(),item(product,1));
    var claim=new CheckoutCartRequest("a".repeat(64),"test-store",List.of(new CheckoutCartRequest.Line(product,null,1)));
    assertThat(operations.claim(cart.id(),claim).status()).isEqualTo(CartStatus.CHECKED_OUT);
    assertThat(operations.claim(cart.id(),claim).status()).isEqualTo(CartStatus.CHECKED_OUT);
    assertThat(operations.claim(cart.id(),new CheckoutCartRequest("b".repeat(64),"test-store",claim.items())).status())
      .isEqualTo(CartStatus.CHECKED_OUT);
    assertThatThrownBy(() -> operations.claim(cart.id(),new CheckoutCartRequest("c".repeat(64),"test-store",
      List.of(new CheckoutCartRequest.Line(UUID.randomUUID(),null,1)))))
      .isInstanceOf(com.metaarch.cart.exception.ConflictException.class);
    assertThatThrownBy(() -> operations.claim(cart.id(),new CheckoutCartRequest("d".repeat(64),"other-store",claim.items())))
      .isInstanceOf(com.metaarch.cart.exception.ConflictException.class);
    assertThatThrownBy(() -> operations.add(cart.id(),UUID.randomUUID().toString(),item(product,1)))
      .isInstanceOf(com.metaarch.cart.exception.InvalidCartStateException.class);
    assertThatThrownBy(() -> service.clear(cart.id())).isInstanceOf(com.metaarch.cart.exception.InvalidCartStateException.class);
  }

  @Test void invalidSnapshotCreatesNoClaimAndConcurrentCartCreationReturnsOneCart() throws Exception {
    UUID customer=UUID.randomUUID();CartRequest request=new CartRequest(customer,"test-store");
    CartResponse result;
    try(var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(() -> service.create(request));var second=executor.submit(() -> service.create(request));
      result=first.get();assertThat(second.get().id()).isEqualTo(result.id());
    }
    assertThatThrownBy(() -> operations.claim(result.id(),new CheckoutCartRequest("c".repeat(64),"other",List.of(new CheckoutCartRequest.Line(UUID.randomUUID(),null,1)))))
      .isInstanceOf(com.metaarch.cart.exception.ConflictException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_checkout_claims WHERE cart_id=?",Integer.class,result.id().toString())).isZero();
    assertThat(service.get(result.id()).status()).isEqualTo(CartStatus.ACTIVE);
  }
}
