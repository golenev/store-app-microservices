# Store App Microservices

Учебный Java 21 / Spring Boot 3 проект с Kafka, PostgreSQL и Redis. Исходная версия появилась после курсов QA.GURU и консультаций [Alexandr056](https://github.com/Alexandr056).

## Текущее состояние

Задачи 1–3 включены в master через PR #37–39: [контракты v1](contracts/README.md), runtime и TARIFFS quote/CRUD/cache. Задача 4 реализует приёмку WAREHOUSE, автоматический pricing и GoodsPosted outbox. Новый STORE пока не применяет GoodsPosted и не списывает остаток.

- **STORE** (`store-service`, package `com.shop.store`) — перенесённый legacy каталог, глобальная корзина, Basic auth и старый `/order`.
- **TARIFFS** (`tariffs-service`) — versioned правила, fractional quote, Redis snapshots без TTL, ручной/плановый reset и fallback на PostgreSQL. Старый percentage list временно сохранён для STORE без отдельного кеша и задержки.
- **WAREHOUSE** (`warehouse-service`, package `com.shop.warehouse`) — принимает DeliveryReceived через Kafka, сохраняет состояние/попытки pricing, получает quote, рассчитывает HALF_UP цену и публикует GoodsPosted из outbox. Есть HTTP-диагностика и технический эмулятор поставщика.

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

Каждый образ собирает свой Maven-модуль на Java 21 и запускается непривилегированным пользователем. Приложения ждут health инфраструктуры; STORE также ждёт TARIFFS. Первый build требует доступа к Docker Hub и Maven Central.

Дополнительный UI в любом режиме:

```shell
docker compose --profile ui up -d kafdrop
```

HTTP health без авторизации: `http://localhost:6789/actuator/health`, `http://localhost:6790/actuator/health`, `http://localhost:6791/actuator/health`. Общий health проверяет БД, у TARIFFS также Redis. TARIFFS `/actuator/health/readiness` проверяет готовность и PostgreSQL; отказ Redis не исключает DB-fallback из обслуживания. Compose использует этот readiness и ждёт запуска Redis, а не его healthy. Kafka проверяется отдельным broker healthcheck. Health WAREHOUSE не подтверждает завершение конкретной поставки: для этого нужен delivery status, а POSTED ещё не означает приход в STORE.

Остановка контейнеров с сохранением данных:

```shell
docker compose --profile apps --profile ui down
```

Compose использует имя проекта `shop-runtime` и отдельные volumes `postgres-data`, `kafka-data`, `redis-data`. Старые volumes прежней конфигурации не удаляются и не подключаются. Если старые контейнеры занимают порты, сначала остановите своё прежнее окружение или задайте `POSTGRES_PORT`, `KAFKA_PORT`, `REDIS_PORT`, `STORE_PORT`, `TARIFFS_PORT`, `WAREHOUSE_PORT`, `KAFDROP_PORT`. Для IDEA при смене портов также меняйте соответствующие URL; `KAFKA_HOST` задаёт адрес external listener.

## Миграции и fixtures

Схемы создаёт Flyway из `src/main/resources/db/migration` каждого сервиса. STORE/TARIFFS используют Hibernate `ddl-auto: validate`; WAREHOUSE работает через JDBC без ORM. SQL init Spring отключён. Автоматический baseline и Flyway clean отключены: подключение к старой непустой схеме без history завершится ошибкой, а не скрытым пересозданием таблиц.

- STORE V1 создаёт прежние `product`, `cart`, `orders` с FK и проверками количества/цены.
- TARIFFS V1/V2 сохраняют прежние `tariffs` и семь процентных fixtures. V3 создаёт `tariff_rules` с UUID, version, bounds и дробной наценкой; V4 добавляет 14 правил для MOSCOW/SPB. Старые миграции не изменены.
- WAREHOUSE V1 создаёт `stores`; V2 добавляет S-1 → MOSCOW и S-2 → SPB; V3 — приёмку, items, inbox/диагностику, sequence и outbox.

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

TARIFFS сохраняет `GET /tariffs?all=true` и legacy CRUD процентных `tariffs` для старого STORE. Этот список читается из БД без задержки и кеша; он независим от новых `tariff_rules`. Изменение новых правил пока не меняет legacy приёмку STORE. `POST /api/v1/resetCache?now=true` — временный alias нового reset с прежним текстом ответа. Ни один reset не переоценивает уже сохранённые товары.

## Тарифный API задачи 3

Без авторизации, на `http://localhost:6790`:

| Endpoint | Результат |
| --- | --- |
| GET `/tariffs/quote?productType=NON_FOOD&purchasePrice=100.00&currency=RUB&cityId=MOSCOW` | `{"markupRate":"0.20","tariffRuleId":"b3000000-0000-4000-8000-000000000001","tariffVersion":1}` для fixtures |
| GET `/tariffs/rules` | `{ "items": [...] }`, текущая БД, до 1000 правил |
| GET `/tariffs/rules/{tariffRuleId}` | Текущее правило или 404 NOT_FOUND |
| POST `/tariffs/rules` | 201, серверный UUID, version=1 и Location |
| PUT `/tariffs/rules/{tariffRuleId}` | Полная замена, version увеличивается под row lock |
| DELETE `/tariffs/rules/{tariffRuleId}` | 204 или 404 NOT_FOUND |
| POST `/tariffs/cache/reset` | `{ "cache":"tariff-quotes", "resetAt":"...Z" }`, либо 503 |

POST/PUT принимают один формат; `upperBound` обязателен, explicit null означает бесконечность:

```json
{"productType":"NON_FOOD","cityId":"MOSCOW","currency":"RUB","lowerBound":"0.00","upperBound":"500.00","markupRate":"0.20"}
```

Деньги — JSON-строки с двумя знаками, наценка — неотрицательная **доля**, 1–6 дробных знаков. `0.20` = 20%; DB использует NUMERIC(9,6). Нижняя граница включена, верхняя исключена. Нет правила → 404 TARIFF_NOT_FOUND; несколько совпадений → 409 TARIFF_AMBIGUOUS. Пересекающиеся правила можно сохранить для диагностики; quote не выбирает произвольный первый результат. CRUD не инвалидирует quote-кеш, даже после удаления правила. Поэтому разные уже заполненные запросы могут показывать разные версии до reset — это согласованное поведение.

| Тип | Диапазон | MOSCOW | SPB |
| --- | --- | --- | --- |
| NON_FOOD | [0, 500) | 0.20 | 0.21 |
| NON_FOOD | [500, 1000) | 0.25 | 0.26 |
| NON_FOOD | [1000, ∞) | 0.30 | 0.31 |
| FOOD | [0, 100) | 0.01 | 0.02 |
| FOOD | [100, 300) | 0.03 | 0.04 |
| FOOD | [300, 500) | 0.05 | 0.06 |
| FOOD | [500, ∞) | 0.10 | 0.11 |

Quote требует положительную purchasePrice, только RUB и точные FOOD/NON_FOOD. cityId регистрозависим; неизвестный город не подменяется MOSCOW. Неизвестные/дублированные JSON-поля, числовые деньги вместо строк, потерянный upperBound и укороченный UUID отклоняются. Невалидный PUT не меняет правило/версию. Создания сериализуются advisory lock в БД, чтобы параллельные запросы не превысили 1000 правил.

Кеш: Redis hash `tariff-quotes:v1:entries`, поля из productType/cityId/currency/нормализованной цены. `tariff-quotes:v1:epoch` хранит UUID поколения. TTL отсутствует; reset атомарно меняет поколение и удаляет только этот hash. Отложенный расчёт старого поколения не заполнит новый кеш, в том числе после потери данных Redis. Другие namespaces и legacy ключи не удаляются. Ошибки NOT_FOUND/AMBIGUOUS не кешируются, повреждённый JSON пересчитывается.

Плановый сброс — `0 0 0 * * *`, `Europe/Moscow`, через тот же reset. Redis read/write timeout — 500 ms, connect timeout — 500 ms. При отказе read/write quote возвращает результат PostgreSQL; при cache miss и отказе БД — 503 DEPENDENCY_UNAVAILABLE. Заполненный quote работает без доступной БД. Manual reset при отказе Redis возвращает 503; запланированный сбой логируется. Ответ 503 не доказывает, что команда reset не была исполнена позднее. Общий health при Redis outage может быть DOWN, readiness с доступной БД остаётся UP.

Это компонент TARIFFS. WAREHOUSE задачи 4 использует quote для HALF_UP расчёта; применение GoodsPosted к остатку STORE относится к задаче 5.

## Приёмка WAREHOUSE задачи 4

В миграции V3 добавлены `deliveries`, `delivery_items`, `received_events`, `delivery_diagnostics`, `warehouse_outbox` и счётчик последовательности в `stores`. V1/V2 и S-1/MOSCOW, S-2/SPB сохранены. Сервис сам объявляет `logistics.deliveries` и `warehouse.goods-posted`. Kafka key равен storeId. Лимит обоих topics/producer/fetch — 16 MiB, чтобы допустимые 1000 строк с описаниями не упирались в стандартный 1 MB.

| Вызов на порту 6791 | Результат |
| --- | --- |
| POST `/technical/deliveries` с целым DeliveryReceived | 202 после Kafka acknowledgement; это отправка поставщика, ещё не POSTED |
| GET `/stores/{storeId}/deliveries/{deliveryId}` | WAITING_PRICING, POSTED или REJECTED; неизвестная поставка/чужой store — 404 |
| POST `/stores/{storeId}/deliveries/{deliveryId}/retry-pricing` | 202 ускоряет следующую попытку WAITING_PRICING; POSTED/REJECTED — 409 |

Пример для чистого учебного окружения; повтор того же документа безопасен:

```shell
curl --fail -H "Content-Type: application/json" --data-binary @contracts/examples/events/delivery-received.json http://localhost:6791/technical/deliveries
curl --fail http://localhost:6791/stores/S-1/deliveries/D-1
```

В PowerShell используйте `curl.exe`. Первый GET может вернуть 404 до приёмки Kafka, затем WAITING_PRICING и POSTED. HTML пока работает через legacy STORE; новое техническое API подключается к нему в задаче 7.

Приёмка сохраняется одной короткой транзакцией. Для учебной версии PostgreSQL advisory lock сериализует ingress, защищая eventId и `(storeId, deliveryId)` даже при одновременных повторах. Новая поставка один раз увеличивает store-scoped deliverySequence и сохраняет receivedAt. Сравнение сортирует строки по lineId и JSON-ключи; eventId/occurredAt транспортной оболочки не входят в бизнес-fingerprint. Изменение имени, описания, количества, цены, типа или идентификаторов значимо. Новый eventId прежнего содержимого допустим; конфликт сохраняется в диагностике и не изменяет результат.

Некорректный разобранный payload с валидными идентификаторами становится REJECTED: original payload доступен в rejectedPayload, items пустой, автоматических попыток нет. Битый JSON, неизвестная версия, неидентифицируемая оболочка, неизвестный магазин и неверный Kafka key сохраняются в `delivery_diagnostics` с topic/partition/offset; tombstone сохраняет nullable raw_message. Ошибка записи распространяется в consumer: RECORD acknowledgement только после commit, error handler повторяет storage failures без конечного discard/recovery.

Pricing worker раз в 500 ms выбирает одну наступившую WAITING_PRICING через `FOR UPDATE SKIP LOCKED`. Сохраняет attemptCount и UUID lease на 30 секунд, затем отпускает транзакцию. HTTP TARIFFS выполняется вне БД-транзакции: connect timeout 1 s, read timeout 2 s. Перед каждой строкой lease продлевается; завершение/ошибка проверяют token, поэтому старый worker после reclaim не пишет результат. После рестарта незавершённая попытка возобновляется по истечении lease; receivedAt/sequence неизменны. Retry endpoint не снимает активный lease.

Отсутствие/неоднозначность правила, отказ или некорректный ответ TARIFFS сохраняют lastError и backoff 1/2/4/8/16/32/60 секунд, далее 60 секунд. Worker автоматически продолжает после исправления зависимости. Продажная цена вычисляется BigDecimal как purchasePrice × (1 + markupRate), HALF_UP до двух знаков. Переполнение денежного формата остаётся WAITING_PRICING с VALIDATION_ERROR до исправления тарифа; результат не обрезается. Успех всех строк одной транзакцией сохраняет цены, POSTED/postedAt и единственный сериализованный GoodsPosted outbox. Ошибка любой строки или записи outbox не оставляет частичных цен.

Один активный экземпляр WAREHOUSE рассчитан на учебный запуск. Pricing и sender работают отдельными scheduler threads. Sender забирает PENDING с persisted lease и отправляет сохранённую строку JSON с прежним eventId/key; после Kafka ack отмечает PUBLISHED. При отказе сохраняет попытку и bounded backoff. Между broker ack и записью PUBLISHED возможна физическая повторная отправка того же события; exactly-once transport не обещается. POSTED означает готовый результат/outbox, а не принятие STORE. SQL-stored lease восстанавливает окна commit→publish и ack→PUBLISHED после рестарта.

Настройки: `warehouse.lease-ms`, `warehouse.pricing-poll-ms`, `warehouse.sender-poll-ms`, `warehouse.workers.enabled`, `warehouse.listener.enabled`. TARIFFS URL — `TARIFFS_BASE_URL`. В тестах scheduled workers можно отключить для управляемых ticks; аварии воспроизводятся SQL triggers/spy только в тестах, HTTP crash-control endpoints отсутствуют.

## Проверки

```shell
mvn -B -ntp test
```

Docker обязателен для интеграционных проверок. Профиль `test` сохраняет Flyway, а для ORM-модулей Hibernate validate; адреса контейнеров задаются через DynamicPropertySource. Тесты не подключаются к Compose-базам.

Историческая проверка задачи 2: на Java 21 выполнено 78 тестов без ошибок, падений и пропусков: 62 контрактных, 7 legacy unit, 3 STORE runtime, 1 legacy ProductFlow, 3 TARIFFS cache/runtime и 2 WAREHOUSE runtime. Проверены bootstrap SQL, запрет доступа к чужим БД, чистые и повторные миграции, fixtures и HTTP health. Legacy ProductFlow использует настоящие Kafka/PostgreSQL и WireMock; cache-тесты — PostgreSQL/Redis. Полный Compose проверяется отдельно от Maven.

[CI задачи 2](https://github.com/golenev/store-app-microservices/actions/runs/37148872985) прошёл для code commit `8d0cd98`: Maven reactor и штатный Docker build всех трёх сервисов, health и пересоздание приложений с сохранностью order/fixtures. Локально также проверены infrastructure-only, запуск контейнеров с JAR, собранными Maven, и повторный старт: health UP, marker сохранён, тарифов 7, магазинов 2. Локальная multi-stage сборка и загрузка optional Kafdrop столкнулись с TLS timeout реестра; это не мешало source-build smoke в CI.

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

Текущий `gradle-wrapper.jar` не имеет main manifest: команды wrapper выше требуют восстановления wrapper в задаче 8. Компиляция `compileKotlin compileTestKotlin` прошла на Java 21 с установленным Gradle 8.8; E2E-сценарии в задаче 2 не запускались. Backend integration и Compose smoke описаны отдельно.

Проверка задачи 3: `mvn -B -ntp test` — 138 тестов, 0 failures/errors/skipped, включая 63 HTTP/PostgreSQL/Redis сценария в `TariffApiTest`. Повторный модульный прогон после изменения readiness — 63/63. Нет утверждений о кеше на основании длительности запроса: проверки считают SQL-вызовы, сверяют snapshots, версии, TTL и данные Redis. Outage воспроизводится pause/unpause реальных контейнеров, каждый тест восстанавливает их в finally.

Проверка задачи 4 (4 октября 2026): `mvn -B -ntp test` — **202 теста**, 0 failures/errors/skipped. WAREHOUSE: 62 `DeliveryIntegrationTest`, 2 `WarehouseRecoveryTest`, 2 runtime. После перехода WAREHOUSE на JDBC и исправления асинхронного ожидания lastError отдельный модульный прогон — **66/66**. Проверены реальные Kafka/PostgreSQL, HTTP WireMock, строгий вход, повторы/конкуренция, HALF_UP/overflow, автоматический retry и рестарт, rollback, storage failure без offset commit, Kafka outage и replay после потерянной отметки ack. Подробная привязка к CASES — в [покрытии](CASES.md). CI Compose smoke дополнен реальным TARIFFS pricing и проверкой одного POSTED/outbox после рестарта; удалённый результат указывается в PR после запуска.
