package com.experience_kafka.controller;

import com.experience_kafka.model.AddToCartRequest;
import com.experience_kafka.model.CartView;
import com.experience_kafka.model.Product;
import com.experience_kafka.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final ProductService productService;
    private final Map<Long, Integer> cartItems = new ConcurrentHashMap<>();

    public CartController(ProductService productService) {
        this.productService = productService;
    }

    @DeleteMapping("/clear")
    public void clearCart() {
        cartItems.clear();
    }

    @PostMapping
    public void addToCart(@Valid @RequestBody AddToCartRequest req) {
        Long barcode = req.barcodeId();
        // проверяем наличие товара
        productService.getProductById(barcode);
        cartItems.merge(barcode, 1, Integer::sum);
    }

    @GetMapping
    public List<CartView> getCartView() {
        return cartItems.entrySet().stream()
                .map(entry -> {
                    Product product = productService.getProductById(entry.getKey());
                    BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                    int quantity = entry.getValue();
                    BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
                    return new CartView(product.getShortName(), price, quantity, total);
                })
                .toList();
    }
}
