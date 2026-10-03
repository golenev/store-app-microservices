package com.shop.store.controller;

import com.shop.store.model.AddToCartRequest;
import com.shop.store.entity.Product;
import com.shop.store.repository.CartItemRepository;
import com.shop.store.entity.CartItem;
import com.shop.store.service.ProductService;
import com.shop.store.model.CartView;
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

    /**
     * Receives product lookup and global-cart persistence dependencies without database side effects.
     */
    public CartController(ProductService productService, CartItemRepository cartRepository) {
        this.productService = productService;
        this.cartRepository = cartRepository;
    }

    /**
     * Deletes all legacy global-cart entries in a repository transaction without changing stock.
     */
    @DeleteMapping("/clear")
    public void clearCart() {
        cartRepository.deleteAll();
    }

    /**
     * Adds one unit of the requested barcode after checking stock. Separate repository transactions retain legacy concurrency behavior until task 5.
     */
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

    /**
     * Returns global-cart entries with current product prices and line totals; it does not reserve stock.
     */
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

    /**
     * Removes one requested unit, deletes an empty entry, or returns 404 for an absent barcode.
     */
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
