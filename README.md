# Store App Microservices

Этот учебный проект демонстрирует интеграцию Spring Boot с Kafka и PostgreSQL. Приложение предоставляет простой веб-интерфейс для работы с товарами и корзиной.

## Состав проекта
- **Spring Boot** приложение на порту `6789`;
- **PostgreSQL** (`localhost:34567`);
- **Kafka** с консолью [Kafdrop](http://localhost:9000) и [Redpanda Console](http://localhost:8081);
- **WireMock** для возможного тестирования сторонних сервисов (шаблоны находятся в `./wiremock/stubs`).

## Запуск
1. Клонируйте репозиторий
   ```bash
   git clone <repo-url>
   ```
2. Поднимите окружение
   ```bash
   docker-compose up -d
   ```
3. Запустите приложение
   ```bash
   mvn spring-boot:run
   ```
4. Откройте [http://localhost:6789/index.html](http://localhost:6789/index.html)

## Основные возможности
- **POST `/api/v1/sendToKafka`** — отправка описания товара в Kafka. Пример:
  ```bash
  curl -X POST \ 
    -H "Content-Type: application/json" \
    -d '{"description":"Чай","price":55}' \
    http://localhost:6789/api/v1/sendToKafka
  ```
- **GET `/api/v1/products`** — список товаров из базы данных.
- **POST `/api/cart`** — добавить товар в корзину (тело запроса `{ "productId": 1 }`).
- **GET `/api/cart`** — содержимое корзины.
- **DELETE `/api/cart/clear`** — очистить корзину.

Сообщения, отправленные в `send-topic`, автоматически сохраняются в базу и доступны через `/api/v1/products` и веб-страницу `products.html`.

Остановить инфраструктуру можно командой `docker-compose down`.
