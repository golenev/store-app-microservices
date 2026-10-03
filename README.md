# Store App Microservices

Учебный Java 21 / Spring Boot 3 проект с Kafka, PostgreSQL и Redis. Исходная версия появилась после курсов QA.GURU и консультаций [Alexandr056](https://github.com/Alexandr056).

## Текущее состояние

Задача 1 включена в master через PR #37: [контракты v1](contracts/README.md), JSON Schema, OpenAPI и 62 проверки. Задача 2 добавляет три запускаемых приложения, Flyway и локальную среду. Новый бизнес-поток ещё не реализован.

- **STORE** (`store-service`, package `com.shop.store`) — перенесённый legacy каталог, глобальная корзина, Basic auth и старый `/order`.
- **TARIFFS** (`tariffs-service`) — прежний список процентных наценок и Redis-кеш. Старые задержки, TTL и правила сброса сохраняются до задачи 3.
- **WAREHOUSE** (`warehouse-service`, package `com.shop.warehouse`) — HTTP runtime, health и fixtures магазинов. Поставки пока не принимает.

Legacy STORE всё ещё принимает сырой Product из `send-topic`, сам запрашивает тарифы и сохраняет продукт по barcode. Здесь пока нет независимых корзин, нового накопления остатка, атомарного списания, идемпотентности и outbox. HTML сохранён и работает через прежние endpoints; адаптация — задача 7.

[План из восьми задач](docs/implementation-plan.md), [CASES](CASES.md), [правила реализации](AGENTS.md), [первоначальное ревью](docs/architecture-review.md). Каждая задача получает отдельную ветку и PR.

Целевая цепочка: DeliveryReceived → WAREHOUSE → HTTP quote TARIFFS → GoodsPosted outbox → STORE → OrderSubmitted outbox. STORE хранит одну позицию на продукт и магазин; новая поставка добавляет количество и меняет цену всего остатка, поздняя старая поставка цену не откатывает. Корзины ничего не резервируют. Оплата, отмены, доставка и авторизация исключены из целевого проекта.

## Локальная среда

Нужны JDK 21, Maven 3.9+ и работающий Docker с Compose v2. Проверьте `java -version`: Java 8 для этого проекта не подходит.

Одна PostgreSQL 16 содержит три отдельные БД и роли. Это разделение владения данными, но не три независимо отказоустойчивых инстанса. Учебные пароли ниже хранятся в bootstrap SQL и Compose.

| Приложение | HTTP | База | Роль / локальный пароль |
| --- | --- | --- | --- |
| STORE | 6789 | store_db | store_app / store_local |
| TARIFFS | 6790 | tariffs_db | tariffs_app / tariffs_local |
| WAREHOUSE | 6791 | warehouse_db | warehouse_app / warehouse_local |

PostgreSQL доступна на `localhost:34567`, Kafka — `localhost:9092`, Redis — `localhost:6379`. Внутри Compose используются `postgres:5432`, `kafka:29092`, `redis:6379` и `http://tariffs-service:6790`. Kafka работает в KRaft без ZooKeeper. Единственный UI — Kafdrop, включается отдельно на порту 9000.

### Инфраструктура в Docker, приложения из IDEA

```shell
docker compose config --quiet
docker compose up -d --wait
```

В IDEA импортируйте корневой `pom.xml`, выберите JDK 21 и запускайте:

1. `com.tariffs.TariffsServiceApplication`.
2. `com.shop.warehouse.WarehouseServiceApplication`.
3. `com.shop.store.StoreServiceApplication`.

Или выполните в трёх терминалах:

```shell
mvn -pl tariffs-service spring-boot:run
mvn -pl warehouse-service spring-boot:run
mvn -pl store-service spring-boot:run
```

Приложения по умолчанию используют localhost и свои роли. Дополнительного профиля для IDEA не требуется. Для другого окружения доступны `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `TARIFFS_BASE_URL`, `REDIS_HOST`, `REDIS_PORT`.

### Полностью в Docker

```shell
docker compose --profile apps config --quiet
docker compose --profile apps up -d --build --wait
docker compose --profile apps ps
```

Каждый образ собирает свой Maven-модуль на Java 21 и запускается непривилегированным пользователем. Приложения ждут health инфраструктуры; STORE также ждёт TARIFFS. Первый build требует доступа к Docker Hub, Maven Central и пакетному репозиторию базового образа.

Дополнительный UI в любом режиме:

```shell
docker compose --profile ui up -d kafdrop
```

HTTP health без авторизации: `http://localhost:6789/actuator/health`, `http://localhost:6790/actuator/health`, `http://localhost:6791/actuator/health`. Общий health проверяет БД, у TARIFFS также Redis. Kafka проверяется отдельным broker healthcheck. Health WAREHOUSE пока не подтверждает обработку поставок.

Остановка контейнеров с сохранением данных:

```shell
docker compose --profile apps --profile ui down
```

Compose использует имя проекта `shop-runtime` и отдельные volumes `postgres-data`, `kafka-data`, `redis-data`. Старые volumes прежней конфигурации не удаляются и не подключаются. Если старые контейнеры занимают порты, сначала остановите своё прежнее окружение или задайте `POSTGRES_PORT`, `KAFKA_PORT`, `REDIS_PORT`, `STORE_PORT`, `TARIFFS_PORT`, `WAREHOUSE_PORT`, `KAFDROP_PORT`. Для IDEA при смене портов также меняйте соответствующие URL; `KAFKA_HOST` задаёт адрес external listener.

## Миграции и fixtures

Схемы создаёт Flyway из `src/main/resources/db/migration` каждого сервиса; Hibernate использует `ddl-auto: validate`. SQL init Spring отключён. Автоматический baseline и Flyway clean отключены: подключение к старой непустой схеме без history завершится ошибкой, а не скрытым пересозданием таблиц.

- STORE V1 создаёт прежние `product`, `cart`, `orders` с FK и проверками количества/цены.
- TARIFFS V1 создаёт прежнюю `tariffs`; V2 однократно добавляет семь процентных тарифов. Скрытый CommandLineRunner больше не заполняет БД.
- WAREHOUSE V1 создаёт `stores`; V2 добавляет S-1 → MOSCOW и S-2 → SPB.

Повторный старт валидирует checksum и не повторяет выполненные миграции. Применённые SQL-файлы не редактируют: изменения схемы оформляют следующей версией миграции.

`infra/postgres/init/01-databases.sql` создаёт БД и роли только при первом запуске нового PostgreSQL volume. Каждая роль подключается только к своей БД; чужие сервисные и служебные БД запрещены. Изменение bootstrap SQL само по себе не обновляет существующий volume. Исторические данные не мигрируются, существующие базы и volumes автоматически не удаляются.

## Legacy API и HTML

Откройте `http://localhost:6789/login.html`, пользователь `user`, пароль `qwerty`. Для прямых API-запросов передавайте Basic auth.

| Endpoint STORE | Текущее действие |
| --- | --- |
| POST `/api/v1/sendToKafka` | Отправить legacy Product в send-topic |
| GET `/api/v1/products` | Прочитать старый каталог |
| POST `/api/cart` | Добавить единицу barcodeId в общую корзину |
| GET `/api/cart` | Прочитать общую корзину |
| POST `/api/cart/decrement` | Уменьшить позицию |
| DELETE `/api/cart/clear` | Очистить общую корзину |
| POST `/order` | Сохранить клиентский snapshot без нового submit |

TARIFFS по-прежнему предоставляет `GET /tariffs?all=true` и `POST /api/v1/resetCache?now=true`. Новые endpoints из OpenAPI добавляются задачами 3–6. Сброс legacy кеша не переоценивает сохранённые товары.

## Проверки

```shell
mvn -B -ntp test
```

Docker обязателен для интеграционных проверок. Профиль `test` сохраняет Flyway и Hibernate validate; адреса контейнеров задаются через DynamicPropertySource. Тесты не подключаются к Compose-базам.

На Java 21 выполнено 78 тестов без ошибок, падений и пропусков: 62 контрактных, 7 legacy unit, 3 STORE runtime, 1 legacy ProductFlow, 3 TARIFFS cache/runtime и 2 WAREHOUSE runtime. Проверены bootstrap SQL, запрет доступа к чужим БД, чистые и повторные миграции, fixtures и HTTP health. Legacy ProductFlow использует настоящие Kafka/PostgreSQL и WireMock; cache-тесты — PostgreSQL/Redis. Полный Compose проверяется отдельно от Maven.

Только контракты, без Docker:

```shell
mvn -pl contract-tests test
```

CI запускает весь Maven reactor и проверяет конфигурацию обоих Compose-режимов. Сквозной CI нового потока — задача 8; текущие CASES не объявляются пройденными по runtime health.
Отдельный Compose smoke job собирает все три образа, проверяет health и сохранность записи/fixtures после пересоздания приложений; сохраняет логи как CI artifact.

Kotlin E2E остаётся набором прежнего API, а не проверкой новой архитектуры. Его STORE JDBC defaults обновлены на store_db; переопределения: `STORE_DB_URL`, `STORE_DB_USER`, `STORE_DB_PASSWORD`. Для старого набора при запущенной среде:

```shell
cd e2e-tests
./gradlew test
./gradlew allureReport
```

Отчёт: `e2e-tests/build/reports/allure-report/index.html`. Полный перенос Kotlin E2E и адаптация HTML идут отдельными задачами.
