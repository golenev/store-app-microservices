package unit;

import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.ProductRepository;
import com.experience_kafka.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * В этом юнит-тесте применяем Mockito: сервис зависит от {@link ProductRepository},
 * поэтому подменяем репозиторий мок-объектом, чтобы изолировать бизнес-логику и
 * описать ожидаемые ответы без реальной базы данных.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    ProductRepository productRepository;

    /**
     * Сервис, который тестируем. Он получает мок-репозиторий через конструктор.
     */
    ProductService productService;

    @BeforeEach
    void setUp() {
        // Перед каждым тестом создаём новый экземпляр сервиса с мокнутым репозиторием.
        productService = new ProductService(productRepository);
    }

    @Test
    @DisplayName("возвращает товар, если он найден по штрихкоду")
    void returnsProductWhenBarcodeExists() {
        // Подготавливаем тестовые данные: объект Product, который должен вернуться из сервиса.
        Product product = new Product();
        product.setBarcodeId(42L);
        // Настраиваем мок-репозиторий: при запросе штрихкода 42 возвращаем подготовленный продукт.
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));

        // Вызываем метод сервиса, который должен обратиться к репозиторию и вернуть наш объект.
        Product result = productService.getProductById(42L);

        // Проверяем, что сервис вернул тот же объект, который предоставил репозиторий.
        assertThat(result).isSameAs(product);
    }

    @Test
    @DisplayName("бросает исключение, если товар не найден")
    void throwsWhenProductMissing() {
        // Настраиваем репозиторий так, чтобы он вернул пустой Optional для отсутствующего товара.
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        // Проверяем, что сервис пробрасывает исключение NoSuchElementException с идентификатором товара в сообщении.
        assertThatThrownBy(() -> productService.getProductById(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }
}
