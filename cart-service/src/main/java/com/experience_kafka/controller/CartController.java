package com.experience_kafka.controller;

import com.experience_kafka.model.AddToCartRequest;
import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.CartItemRepository;
import com.experience_kafka.entity.CartItem;
import com.experience_kafka.service.ProductService;
import com.experience_kafka.model.CartView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        Long barcode = req.barcodeId(); // получаем штрихкод из тела запроса
        Product product = productService.getProductById(barcode); // ищем товар по штрихкоду

        cartRepository.findById(barcode) // пытаемся найти товар в корзине
                .ifPresentOrElse(item -> { // если нашли, выполняем этот блок
                    if (item.getQuantity() >= product.getQuantity()) { // если количество в корзине уже максимальное
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Not enough product in stock"); // сообщаем, что товара больше нет
                    }
                    item.setQuantity(item.getQuantity() + 1); // увеличиваем количество в корзине
                    cartRepository.save(item); // сохраняем обновленный товар
                }, () -> { // если по id не удалось найти товар, то выполняем этот блок
                    if (product.getQuantity() <= 0) { // если товара нет на складе
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Not enough product in stock"); // сообщаем об ошибке
                    }
                    cartRepository.save(new CartItem(barcode, 1)); // добавляем новый товар в корзину
                });
    }

    @GetMapping
    public List<CartView> getCartView() {
        return cartRepository.findAll().stream()
                .map(item -> {
                    Product product = productService.getProductById(item.getBarcodeId());
                    BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                    int quantity = item.getQuantity();
                    BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
                    return new CartView(item.getBarcodeId(), product.getShortName(), price, quantity, total);
                })
                .toList();
    }

    @PostMapping("/decrement")
    public void decrementFromCart(@Valid @RequestBody AddToCartRequest req) {
        Long barcode = req.barcodeId();
        cartRepository.findById(barcode)
                .ifPresentOrElse(item -> {
                    if (item.getQuantity() <= 1) {
                        cartRepository.delete(item);
                    } else {
                        item.setQuantity(item.getQuantity() - 1);
                        cartRepository.save(item);
                    }
                }, () -> {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not in cart");
                });
    }
}
