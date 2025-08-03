package com.experience_kafka.controller;

import com.experience_kafka.model.AddToCartRequest;
import com.experience_kafka.model.CartView;
import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.CartItemRepository;
import com.experience_kafka.entity.CartItem;
import com.experience_kafka.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final ProductService productService;
    private final CartItemRepository cartRepository;

    public CartController(ProductService productService, CartItemRepository cartRepository) {
        this.productService = productService;
        this.cartRepository = cartRepository;
    }

    @DeleteMapping("/clear")
    public void clearCart() {
        cartRepository.deleteAll();
    }

    @PostMapping
    public void addToCart(@Valid @RequestBody AddToCartRequest req) {
        Long barcode = req.barcodeId();
        // проверяем наличие товара
        productService.getProductById(barcode);
        cartRepository.findById(barcode)
                .ifPresentOrElse(item -> {
                    item.setQuantity(item.getQuantity() + 1);
                    cartRepository.save(item);
                }, () -> cartRepository.save(new CartItem(barcode, 1)));
    }

    @GetMapping
    public List<CartView> getCartView() {
        return cartRepository.findAll().stream()
                .map(item -> {
                    Product product = productService.getProductById(item.getBarcodeId());
                    BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                    int quantity = item.getQuantity();
                    BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
                    return new CartView(product.getShortName(), price, quantity, total);
                })
                .toList();
    }
}
