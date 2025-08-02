package com.experience_kafka.controller;

import com.experience_kafka.model.AddToCartRequest;
import com.experience_kafka.model.CartItem;
import com.experience_kafka.model.CartView;
import com.experience_kafka.repository.CartItemRepository;
import com.experience_kafka.service.ProductService;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartItemRepository cartItemRepository;
    private final ProductService productService;

    public CartController(CartItemRepository cartItemRepository,
                          ProductService productService) {
        this.cartItemRepository = cartItemRepository;
        this.productService = productService;
    }

    @DeleteMapping("/clear")
    public void clearCart () {
        cartItemRepository.deleteAll();
    }

    @PostMapping
    public void addToCart(@Valid @RequestBody AddToCartRequest req) {
        Long barcode = req.barcodeId();
        // подтягиваем актуальные данные по товару из БД
        ProductDto product = productService.getProductById(barcode);

        // ищем существующую запись
        CartItem item = cartItemRepository.findByBarcodeId(barcode);
        if (item == null) {
            item = new CartItem();
            item.setBarcodeId(product.barcodeId());
            item.setDescription(product.description());
            item.setPrice(product.price());
            item.setQuantity(1);
        } else {
            item.setQuantity(item.getQuantity() + 1);
        }
        item.setAddedAt(LocalDateTime.now());
        cartItemRepository.save(item);
    }

    @GetMapping
    public List<CartView> getCartView() {
        return cartItemRepository.findAll().stream()
                .map(item -> {
                    // подстраховка на случай, если в БД price == null
                    BigDecimal price = item.getPrice() != null
                            ? item.getPrice()
                            : BigDecimal.ZERO;
                    BigDecimal total = price.multiply(BigDecimal.valueOf(item.getQuantity()));
                    return new CartView(
                            item.getDescription(),
                            price,
                            item.getQuantity(),
                            total
                    );
                })
                .toList();
    }

    public record ProductDto(Long barcodeId,
                              String description,
                              BigDecimal price,
                              boolean isFoodstuff,
                              OffsetDateTime arrivalTime,
                              int quantity) {}
}