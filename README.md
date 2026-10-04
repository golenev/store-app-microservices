# Store App Microservices

Учебный Java 21 / Spring Boot 3 проект с Kafka, PostgreSQL и Redis. Исходная версия появилась после курсов QA.GURU и консультаций [Alexandr056](https://github.com/Alexandr056).

## Текущее состояние

Задачи 1–5 включены в master через PR #37–41: [контракты v1](contracts/README.md), runtime, TARIFFS, WAREHOUSE и STORE inventory/корзины. Задача 6 реализует атомарный submit, идемпотентность, snapshot и OrderSubmitted outbox. Адаптация HTML — задача 7, Kotlin E2E — задача 8.

- **STORE** (`store-service`, package `com.shop.store`) — единственный владелец inventory, приходов, независимых корзин и принятых заявок. Принимает GoodsPosted, атомарно списывает при submit и автоматически публикует OrderSubmitted; не вызывает TARIFFS. API без авторизации.
- **TARIFFS** (`tariffs-service`) — versioned правила, fractional quote, Redis snapshots без TTL, ручной/плановый reset и fallback на PostgreSQL. Старые процентные endpoints удалены.
- **WAREHOUSE** (`warehouse-service`, package `com.shop.warehouse`) — принимает DeliveryReceived через Kafka, сохраняет состояние/попытки pricing, получает quote, рассчитывает HALF_UP цену и публикует GoodsPosted из outbox. Есть HTTP-диагностика и технический эмулятор поставщика.

Сырой Product listener, общая корзина, Basic auth и старый `/order` удалены. HTML-файлы сохранены, но пока вызывают удалённые endpoints и не обеспечивают рабочий пользовательский поток; адаптация — задача 7. Backend-поток доступен через REST/Kafka.

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

Каждый образ собирает свой Maven-модуль на Java 21 и запускается непривилегированным пользователем. Приложения ждут health инфраструктуры; WAREHOUSE также ждёт TARIFFS. STORE зависит только от PostgreSQL и Kafka. Первый build требует доступа к Docker Hub и Maven Central.

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

Схемы создаёт Flyway из `src/main/resources/db/migration` каждого сервиса. Все три сервиса работают через JDBC без ORM. SQL init Spring отключён. Автоматический baseline и Flyway clean отключены: подключение к старой непустой схеме без history завершится ошибкой, а не скрытым пересозданием таблиц.

- STORE V1 сохраняет прежние `product`, `cart`, `orders`, которые больше не обслуживаются API. V2 создаёт `store_scopes` (S-1/S-2), `stock_receipts`, `inventory`, `processed_events`, `stock_movements`, `incoming_goods_diagnostics`, `carts`, `cart_items`. V3 добавляет `submissions`, `stock_expenses`, `store_outbox`; исторические записи не переносятся в новую модель.
- TARIFFS V1/V2 сохраняют прежние `tariffs` и семь исторических процентных fixtures без legacy API. V3 создаёт `tariff_rules` с UUID, version, bounds и дробной наценкой; V4 добавляет 14 правил для MOSCOW/SPB. Старые миграции не изменены.
- WAREHOUSE V1 создаёт `stores`; V2 добавляет S-1 → MOSCOW и S-2 → SPB; V3 — приёмку, items, inbox/диагностику, sequence и outbox.

Повторный старт валидирует checksum и не повторяет выполненные миграции. Применённые SQL-файлы не редактируют: изменения схемы оформляют следующей версией миграции.

`infra/postgres/init/01-databases.sql` создаёт БД и роли только при первом запуске нового PostgreSQL volume. Каждая роль подключается только к своей БД; чужие сервисные и служебные БД запрещены. Изменение bootstrap SQL само по себе не обновляет существующий volume. Исторические данные не мигрируются, существующие базы и volumes автоматически не удаляются.

## Каталог и корзины STORE задачи 5

API на `http://localhost:6789` без авторизации. Магазины S-1/S-2 заданы fixtures; создание других магазинов через публичный API не предусмотрено. UUID корзины разделяет покупателей, а знание UUID даёт доступ к учебной корзине.

| Endpoint | Результат |
| --- | --- |
| GET `/stores/{storeId}/catalog` | Одна позиция на productId, стабильный stockItemId, текущая цена и availableQuantity, включая нулевой остаток |
| POST `/stores/{storeId}/carts` без body | 201, новая OPEN-корзина с version=0 и Location |
| GET `/stores/{storeId}/carts/{cartId}` | Состав/version и сумма по текущим ценам из одного repeatable-read snapshot |
| PUT `/stores/{storeId}/carts/{cartId}/items/{stockItemId}` | Полная замена количества позиции; body `{"quantity":3,"expectedCartVersion":0}` |
| DELETE `/stores/{storeId}/carts/{cartId}/items/{stockItemId}?expectedCartVersion=1` | Удаление существующей позиции и новая версия корзины |

После поставки примера WAREHOUSE:

```shell
curl --fail http://localhost:6789/stores/S-1/catalog
curl --fail -X POST http://localhost:6789/stores/S-1/carts
curl --fail -X PUT -H "Content-Type: application/json" -d '{"quantity":3,"expectedCartVersion":0}' http://localhost:6789/stores/S-1/carts/CART_UUID/items/STOCK_UUID
```

Подставьте cartId из POST и stockItemId из каталога. В PowerShell используйте `curl.exe`; правила quoting JSON зависят от версии shell. Не передавайте цены/суммы от клиента. PUT заменяет количество, не прибавляет к нему. Успешный PUT, даже с прежним количеством, и DELETE увеличивают version на 1. Устаревшая version → 409 CART_VERSION_CONFLICT; отсутствующая строка DELETE → 404 без изменения version. Количество больше текущего остатка → 409 INSUFFICIENT_STOCK. Чужой магазин/cart/stock → 404.

Изменение корзины блокирует её строку, проверяет version и для PUT блокирует inventory. Остаток не меняется: при availableQuantity=5 две корзины могут независимо содержать по 5. Новая поставка может изменить цену OPEN-корзины, но не version её состава. Submit проверяет остатки повторно и фиксирует принятые цены.

Приход фиксирует receipt, inventory, движения и eventId одной транзакцией. STORE защищает eventId и `(storeId, deliveryId)` независимо: новый eventId того же нормализованного содержимого не создаёт второй приход. Изменённое содержимое и повторный deliverySequence другой поставки диагностируются без изменения остатка. Сначала берётся event advisory lock, затем строка магазина, затем затронутый inventory в порядке stockItemId. Приходы одного магазина сериализованы; это сознательная граница учебной реализации.

Уникальный приход всегда добавляет quantity. Цена/имя/описание меняются только при большем неизменяемом deliverySequence WAREHOUSE. Поздняя старая поставка добавляет товар, сохраняя последнюю цену. Противоречивый productType одного productId, переполнение количества и превышение 1000 SKU отвергают весь приход. Строгий JSON, денежная формула HALF_UP и UTC-времена проверяются до применения. Некорректные сообщения сохраняются в `incoming_goods_diagnostics` по topic/partition/offset; сбой записи повторяется consumer без преждевременного offset commit. Лимит Kafka topic/fetch — 16 MiB.

Каталог/корзина ограничены 1000 позициями, quantity — положительный int, version — безопасный JSON integer. Цена допускает 26 цифр целой части, сумма — 36. Изменение корзины с переполнением суммы откатывается с 400. Если последующая переоценка делает уже существующую OPEN-корзину непредставимой в формате v1, GET возвращает 503; удаление/уменьшение состава может восстановить допустимую сумму. Суммы не обрезаются и не преобразуются в double.

Старые STORE endpoints, raw Product listener и TARIFFS `/tariffs?all=true`/процентный CRUD/`/api/v1/resetCache` удалены. Новая цепочка использует WAREHOUSE и fractional quote. HTML сохранён для адаптации в задаче 7 и сейчас несовместим с новым API.

## Оформление заявки STORE задачи 6

| Endpoint на порту 6789 | Результат |
| --- | --- |
| POST `/stores/{storeId}/carts/{cartId}/submit` | JSON `{"expectedCartVersion":1}` и обязательный Idempotency-Key; 202 после commit, submissionId/eventId, acceptedAt и PENDING/PUBLISHED |
| GET `/stores/{storeId}/submissions/{submissionId}` | Текущий статус публикации; PUBLISHED содержит publishedAt; чужой scope/неизвестный UUID → 404 |
| GET принятой корзины | SUBMITTED, version после закрытия, submissionId и неизменяемый принятый состав/цены/сумма |

```shell
curl --fail -X POST -H "Content-Type: application/json" -H "Idempotency-Key: operation-001" -d '{"expectedCartVersion":1}' http://localhost:6789/stores/S-1/carts/CART_UUID/submit
curl --fail http://localhost:6789/stores/S-1/submissions/SUBMISSION_UUID
```

Тело не принимает цену, сумму или состав. Ключ регистрозависим, 1–128 символов `[A-Za-z0-9._:-]`, уникален в магазине; UUID подходит. Fingerprint фиксирует storeId/cartId/expectedCartVersion. Повтор принятого запроса возвращает прежний submission с актуальным publicationStatus **до проверки закрытой корзины и её новой версии**. Повтор сохраняет прежние ключ и expectedCartVersion, включая восстановление после потерянного HTTP-ответа. Изменённый запрос под прежним ключом → 409 IDEMPOTENCY_KEY_REUSED. Новый ключ закрытой корзины → 409 CART_ALREADY_SUBMITTED. Не принятый запрос ключ не занимает; после пополнения можно повторить его.

Acceptance выполняется отдельной короткой транзакцией: блокировка корзины, проверка version, блокировка всех её inventory в порядке stockItemId, повторная проверка количества и расчёт BigDecimal по текущим ценам. Пустая/переполненная корзина → 400; stale version → 409 CART_VERSION_CONFLICT; нехватка любой строки → 409 INSUFFICIENT_STOCK без частичного расхода. Гонка за последнюю единицу допускает одну покупку. При version=9007199254740991 дальнейший submit запрещён, чтобы закрытие не создало небезопасный JSON integer.

Submission UNIQUE(storeId, idempotencyKey)/UNIQUE(cartId), списание всех SKU, `stock_expenses`, неизменяемые cart snapshot/OrderSubmitted outbox и SUBMITTED/version+1 фиксируются вместе. Неудачная запись откатывает всё. Конкурентный UNIQUE обрабатывается после завершения rollback: отдельная транзакция читает принятого победителя и сверяет fingerprint. Открытая корзина не резервирует и не фиксирует цену; принятый snapshot не меняется от следующей поставки.

202 и Location означают принятую операцию, а не оплату/исполнение. Ответ 503 с неопределённым исходом commit не доказывает отсутствие списания: повторите прежний ключ и version. Если submissionId уже известен, GET восстанавливает статус.

Sender выбирает один наступивший PENDING через `FOR UPDATE SKIP LOCKED`, сохраняет UUID lease/attemptCount и отправляет сохранённый JSON в `store.order-submitted` с key=storeId вне SQL-транзакции. Kafka ack ожидается до 5 s; max.block 3 s, request timeout 3 s, delivery timeout 7 s. После ack текущий token отмечает PUBLISHED/publishedAt. Ошибка сохраняет PENDING/lastError и backoff 1/2/4/8/16/32/60 s, далее 60 s. Истёкшая lease восстанавливается после рестарта. Потерянная отметка после ack допускает физический повтор с прежним eventId; внешний получатель должен дедуплицировать eventId/submissionId. Retry sender не касается inventory и не возвращает товар по таймауту.

Настройки: `store.sender.enabled` (по умолчанию true), `store.sender-poll-ms` (500), `store.lease-ms` (30000, минимум 10000). Ручного purchase retry/sender-control API нет. Отключение scheduled sender в тестах позволяет воспроизводить crash windows; автоматический retry и restart проверяются отдельными реальными контекстами. В этой учебной версии один активный STORE обслуживает локальный запуск; leases защищают случайные пересечения sender. Topic/producer/fetch используют лимит 16 MiB для допустимых больших сообщений.

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

WAREHOUSE использует quote для HALF_UP расчёта; STORE применяет уже рассчитанный GoodsPosted без HTTP-вызова TARIFFS. Сброс кеша сам по себе не меняет цены inventory.

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

В PowerShell используйте `curl.exe`. Первый GET может вернуть 404 до приёмки Kafka, затем WAITING_PRICING и POSTED. После POSTED отдельно дождитесь товара в STORE `/stores/S-1/catalog`: доставка между сервисами асинхронна. Техническое API подключается к HTML в задаче 7.

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

Docker обязателен для интеграционных проверок. Профиль `test` сохраняет Flyway; адреса контейнеров задаются через DynamicPropertySource. Тесты не подключаются к Compose-базам.

Историческая проверка задачи 2: на Java 21 выполнено 78 тестов без ошибок, падений и пропусков: 62 контрактных, 7 legacy unit, 3 STORE runtime, 1 legacy ProductFlow, 3 TARIFFS cache/runtime и 2 WAREHOUSE runtime. Проверены bootstrap SQL, запрет доступа к чужим БД, чистые и повторные миграции, fixtures и HTTP health. Legacy ProductFlow использует настоящие Kafka/PostgreSQL и WireMock; cache-тесты — PostgreSQL/Redis. Полный Compose проверяется отдельно от Maven.

[CI задачи 2](https://github.com/golenev/store-app-microservices/actions/runs/37148872985) прошёл для code commit `8d0cd98`: Maven reactor и штатный Docker build всех трёх сервисов, health и пересоздание приложений с сохранностью order/fixtures. Локально также проверены infrastructure-only, запуск контейнеров с JAR, собранными Maven, и повторный старт: health UP, marker сохранён, тарифов 7, магазинов 2. Локальная multi-stage сборка и загрузка optional Kafdrop столкнулись с TLS timeout реестра; это не мешало source-build smoke в CI.

Только контракты, без Docker:

```shell
mvn -pl contract-tests test
```

CI запускает весь Maven reactor и проверяет конфигурацию обоих Compose-режимов. Compose smoke собирает три образа, выполняет DeliveryReceived → WAREHOUSE → TARIFFS → GoodsPosted → STORE, создаёт две независимые корзины без резерва, принимает submit на 3 из 10 единиц, ждёт PUBLISHED и повторяет исходный ключ без нового расхода. После пересоздания приложений сверяет остаток 7, закрытый snapshot, одну expense/outbox и fixtures; сохраняет логи. Полный Kotlin/browser E2E остаётся задачей 8.

Kotlin E2E пока использует удалённый legacy API и не совместим с текущей версией. Перенос — задача 8; следующие команды станут проверкой новой архитектуры после переноса и восстановления wrapper:

```shell
cd e2e-tests
./gradlew test
./gradlew allureReport
```

Отчёт: `e2e-tests/build/reports/allure-report/index.html`. Полный перенос Kotlin E2E и адаптация HTML идут отдельными задачами.

Текущий `gradle-wrapper.jar` не имеет main manifest: команды wrapper выше требуют восстановления wrapper в задаче 8. Компиляция `compileKotlin compileTestKotlin` прошла на Java 21 с установленным Gradle 8.8; E2E-сценарии в задаче 2 не запускались. Backend integration и Compose smoke описаны отдельно.

Проверка задачи 3: `mvn -B -ntp test` — 138 тестов, 0 failures/errors/skipped, включая 63 HTTP/PostgreSQL/Redis сценария в `TariffApiTest`. Повторный модульный прогон после изменения readiness — 63/63. Нет утверждений о кеше на основании длительности запроса: проверки считают SQL-вызовы, сверяют snapshots, версии, TTL и данные Redis. Outage воспроизводится pause/unpause реальных контейнеров, каждый тест восстанавливает их в finally.

Проверка задачи 4 (4 октября 2026): `mvn -B -ntp test` — **202 теста**, 0 failures/errors/skipped. WAREHOUSE: 62 `DeliveryIntegrationTest`, 2 `WarehouseRecoveryTest`, 2 runtime. После перехода WAREHOUSE на JDBC и исправления асинхронного ожидания lastError отдельный модульный прогон — **66/66**. Проверены реальные Kafka/PostgreSQL, HTTP WireMock, строгий вход, повторы/конкуренция, HALF_UP/overflow, автоматический retry и рестарт, rollback, storage failure без offset commit, Kafka outage и replay после потерянной отметки ack. Подробная привязка к CASES — в [покрытии](CASES.md). CI Compose smoke дополнен реальным TARIFFS pricing и проверкой одного POSTED/outbox после рестарта; удалённый результат указывается в PR после запуска.

Проверка задачи 5 (4 октября 2026): `mvn -B -ntp test` — **268 тестов**, 0 failures/errors/skipped: 62 contract, 77 STORE (74 inventory/cart + 3 runtime), 63 TARIFFS, 66 WAREHOUSE. STORE использует реальные PostgreSQL/Kafka и HTTP: дубли/конфликты, порядок цен, независимость и version races корзин, согласованное конкурентное чтение, rollback, сохранение диагностики до Kafka offset commit, 1000 SKU и денежные границы. TARIFFS отдельно прошёл `mvn -B -ntp -pl tariffs-service clean test` — 63/63, включая отсутствие legacy endpoints. Удалены устаревшие STORE тесты прежней модели; исторические результаты выше не обозначают доступность старого API. CI Compose smoke проверяет новую цепочку до корзин; результат удалённого запуска указывается в PR.

Проверка задачи 6 (4 октября 2026): `mvn -B -ntp test` — **309/309**, 0 failures/errors/skipped: contracts 62, STORE 118, TARIFFS 63, WAREHOUSE 66. Отдельный STORE прогон — **118/118**; добавлены 39 HTTP/SQL/Kafka submission-сценариев и 2 автоматических recovery/restart-сценария. Проверены настоящий конфликт UNIQUE с отдельным replay transaction, конкурентные покупки/PUT/приходы, rollback каждой стадии acceptance, точный immutable snapshot, Kafka outage и повтор после утраты PUBLISHED, lease fencing и >1 MiB OrderSubmitted из 1000 строк. Consumer-offset тест теперь ждёт появления committed offset без NPE. Compose CI расширен до submit/PUBLISHED/повтора ключа и сохранности расхода после пересоздания приложений; удалённый результат указывается в PR.
