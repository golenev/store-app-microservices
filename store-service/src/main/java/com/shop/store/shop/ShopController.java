package com.shop.store.shop;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static com.shop.store.shop.ShopModels.*;

/** Public scope-aware catalog/cart API; purchase submission and legacy bypass endpoints are absent in this task. */
@RestController
@RequestMapping("/stores/{storeId}")
public class ShopController {
    private final CartService carts;
    private final ShopCodec codec;
    /** Receives transactional cart operations and strict protocol parsing; authentication is outside the agreed project. */
    public ShopController(CartService carts,ShopCodec codec) { this.carts=carts; this.codec=codec; }
    /** Returns one SKU per product, including zero stock and the last applied sequence's price/metadata. */
    @GetMapping("/catalog")
    public Catalog catalog(@PathVariable String storeId) { return carts.catalog(codec.identifier(storeId)); }
    /** Creates an independent empty cart with Location; unsolicited body fields cannot assign prices, totals or ownership. */
    @PostMapping("/carts")
    public ResponseEntity<Cart> create(@PathVariable String storeId,@RequestBody(required=false) String raw) {
        if(raw!=null && !raw.isBlank()) throw new ShopException(400,"VALIDATION_ERROR","Cart creation does not accept a body");
        Cart cart=carts.create(codec.identifier(storeId));
        return ResponseEntity.created(URI.create("/stores/"+cart.storeId()+"/carts/"+cart.cartId())).body(cart);
    }
    /** Reads one scope-owned cart with current prices and a consistent composition/version snapshot. */
    @GetMapping("/carts/{cartId}")
    public Cart get(@PathVariable String storeId,@PathVariable String cartId) {
        return carts.get(codec.identifier(storeId),codec.uuid(cartId));
    }
    /** Replaces the line's total quantity, requiring the exact version and current stock; response is the committed cart state. */
    @PutMapping(value="/carts/{cartId}/items/{stockItemId}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Cart put(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,@RequestBody String raw) {
        return carts.put(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.put(raw));
    }
    /** Deletes only an existing line using the mandatory query version; successful deletion advances the cart once. */
    @DeleteMapping("/carts/{cartId}/items/{stockItemId}")
    public Cart delete(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,
                       @RequestParam String expectedCartVersion) {
        return carts.delete(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.version(expectedCartVersion));
    }
}
