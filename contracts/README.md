# Контракты магазина v1

Статус: контракты зафиксированы задачей 1. TARIFFS реализован задачей 3, WAREHOUSE приёмка/pricing/outbox и техническое API — задачей 4. STORE GoodsPosted/каталог/OPEN-корзины реализованы задачей 5; submit, SUBMITTED snapshot, submission status и OrderSubmitted outbox — задачей 6. HTML — задача 7. Legacy endpoints удалены.

## Файлы и проверка

- `schemas/shop-v1.schema.json` — JSON Schema Draft 2020-12: три Kafka-события и именованные определения HTTP-запросов/ответов в `$defs`.
- `openapi.json` — OpenAPI 3.1 с внешними ссылками на ту же схему. Его можно открыть в редакторе OpenAPI; ссылки разрешаются относительно файла.
- `examples/events` — три сообщения сквозного примера: 10 единиц по 100.00, продажная цена 120.00, заявка на 3 единицы/360.00.
- `examples/http` — запросы и состояния API. `examples/manifest.json` связывает каждый пример с проверяемым определением схемы.
- `../contract-tests` — отдельный Maven-модуль только с тестами. Это не общая production-библиотека DTO/JPA.

```shell
mvn -pl contract-tests test
```

Нужна Java 21. Docker, PostgreSQL, Kafka, Redis и запущенные приложения для этой команды не нужны. Тесты проверяют схемы, примеры, DTO round trip, отрицательные входы и разбор OpenAPI с разрешением ссылок. Они не доказывают транзакционную или сквозную корректность будущих сервисов.

## Общие правила wire-формата

JSON передаётся в UTF-8. В v1 неизвестные поля отклоняются (`additionalProperties: false`). Отсутствующие необязательные поля опускаются; явный null допустим только там, где указан схемой, например upperBound тарифного правила.

- storeId, cityId, productId, deliveryId и lineId — регистрозависимые строки длиной 1–64 из латинских букв, цифр, `.`, `_`, `:`, `-`. Первым символом должна быть буква/цифра. Пробелы не допускаются.
- eventId, stockItemId, cartId, submissionId и tariffRuleId — UUID в строковом виде. Поставщик генерирует eventId входящего события; сервисы генерируют свои исходящие eventId один раз до записи outbox.
- quantity поставки/корзины/заявки — целое от 1 до 2147483647. availableQuantity — целое от 0 до 2147483647. Переполнение суммарного остатка должно отклоняться атомарно на стороне сервиса.
- version, expectedCartVersion и deliverySequence — целые не больше 9007199254740991, чтобы JSON-клиент JavaScript мог читать их без потери целочисленной точности. Cart version начинается с 0; тарифная версия и sequence — с 1.
- Все timestamps — ISO 8601 UTC с завершающим `Z`, например `2026-10-03T10:00:00Z`; дробные секунды разрешены. LocalDateTime без зоны в контракт не входит.
- Валюта первой версии — только `RUB`. productType — `FOOD` или `NON_FOOD`; цена не входит в имя типа.
- Цены — десятичные строки с двумя знаками: `"100.00"`. Закупочная/продажная цена положительна; тарифная lowerBound может быть `"0.00"`. Максимальная длина целой части цены — 26 цифр; для сумм — 36, совместимо с NUMERIC(38,2). Вычисленный результат за пределами формата не сохраняется молча.
- Наценка — неотрицательная доля в строке, например `"0.20"`, с 1–6 знаками после точки и до 3 цифр перед ней. Это 20%, а не старое значение `20`. В БД/нормализованном представлении использовать scale=6.
- Продажная цена: `purchasePrice × (1 + markupRate)`, округление до scale=2 по HALF_UP. lineTotal = unitPrice × quantity; totalAmount = сумма lineTotal. Для вычислений — BigDecimal, без double.

Длина строк/коллекций ограничена схемой: до 1000 позиций на сообщение/ответ, shortName до 255, description до 2000 символов. Поскольку первая версия учебная, каталог и список правил ограничены 1000 позициями; сервис не должен молча обрезать ответ или выдавать некорректный JSON. Fixtures остаются в пределах этого ограничения; пагинация потребует отдельного расширения контракта.

## Kafka

Оболочка каждого события: eventId, eventType, schemaVersion=1, occurredAt, storeId и payload. Ключ сообщения — storeId. Порядок между топиками не предполагается. Outbox сохраняет сериализованное событие: повтор отправки не пересчитывает его цены, identifiers или время.

| Топик | Событие | Источник → получатель | Назначение времени occurredAt |
| --- | --- | --- | --- |
| logistics.deliveries | DeliveryReceived | Поставщик/эмулятор → WAREHOUSE | Время формирования события поставщиком |
| warehouse.goods-posted | GoodsPosted | WAREHOUSE → STORE | Равно postedAt |
| store.order-submitted | OrderSubmitted | STORE → внешний обработчик | Равно acceptedAt |

### DeliveryReceived

payload содержит deliveryId и непустой items. Каждая строка содержит lineId, productId, productType, shortName, description, quantity, purchasePrice и currency. deliveryId уникален в storeId; lineId и productId уникальны внутри одной поставки. Две строки одного productId отклоняются, а не суммируются с неоднозначным выбором цены.

Имя/описание передаются поставщиком. Они не должны интерпретироваться HTML-страницей как разметка. shortName проверяется на непустое содержимое, а не только на длину; description может быть пустым.

### GoodsPosted

payload содержит deliveryId, deliverySequence, receivedAt, postedAt и items. Строки сохраняют поля поставки и добавляют markupRate, tariffRuleId, tariffVersion и salePrice. Товар целиком оприходуется только после расчёта всех строк.

WAREHOUSE выделяет deliverySequence при первом сохранении поставки внутри магазина. Sequence не меняется при retry, включая рестарт; пропуски допустимы. receivedAt — время первого сохранения WAREHOUSE, postedAt — время перехода в POSTED; postedAt не раньше receivedAt.

В GoodsPosted нет stockItemId: STORE назначает его при первом приходе продукта в магазине. UNIQUE(storeId, productId) сохраняет одну inventory-позицию. Любой уникальный приход добавляет quantity; цена и отображаемые название/описание обновляются только при большем deliverySequence. ProductType одного productId должен быть согласован между поставками; противоречивый тип диагностируется и не применяется молча.

Если поставка А(sequence=1) завершилась после Б(sequence=2), приход А добавляет количество, но сохраняется цена Б. Поставка, ожидающая pricing, ещё не влияет на цену. Сброс тарифного кеша сам по себе не переоценивает остатки.

### OrderSubmitted

payload содержит submissionId, cartId, acceptedAt, items, totalAmount и currency. Строка содержит stockItemId, productId, shortName, quantity, unitPrice и lineTotal. Это неизменяемый snapshot принятой заявки, уже уменьшившей остаток. Платёж/доставка/отмена в контракт не входят.

### Повторы и конфликты

WAREHOUSE защищает `(storeId, deliveryId)`. STORE защищает eventId и `(storeId, deliveryId)` независимо: новый eventId прежнего результата поставки не создаёт новый приход. Один и тот же идентификатор с другим бизнес-содержимым — конфликт, а не обновление существующего результата.

Для fingerprint сортировать строки по lineId, сравнивать нормализованные денежные значения/markupRate и timestamps как Instant; порядок JSON-полей не значим. Изменение имени/описания, количества, цены, идентификаторов строки/продукта или типа значимо. Из fingerprint поставки исключить eventId и occurredAt оболочки: новый транспортный envelope допустим для прежнего бизнес-содержимого. Для GoodsPosted также сравнивать deliverySequence, receivedAt, postedAt и рассчитанные тарифные поля.

При повторе одного eventId его eventType и storeId не должны меняться. Дедупликация, fingerprint и приход фиксируются одной транзакцией. Одна физическая запись в Kafka не обещается; внешний обработчик OrderSubmitted должен распознавать submissionId/eventId.

## HTTP и состояния

Полный список методов, параметров и ответов — в `openapi.json`. Теги TARIFFS/WAREHOUSE/STORE обозначают разные приложения; единого gateway эта версия не требует. Порты/Compose URL будут зафиксированы в задаче 2.

### Тарифы

- GET /tariffs/quote: обязательны productType, purchasePrice, currency и cityId. Результат — markupRate, tariffRuleId, tariffVersion.
- /tariffs/rules и /tariffs/rules/{tariffRuleId}: список, чтение, создание, полная замена и удаление правила.
- lowerBound включительно, upperBound исключительно; null upperBound означает отсутствие верхней границы. При конечной границе lowerBound < upperBound. Нет совпадения — 404 TARIFF_NOT_FOUND; несколько — 409 TARIFF_AMBIGUOUS. Положительность наценки не подставляется вместо ошибки.
- Создание назначает UUID и version=1; каждое успешное PUT увеличивает version. Заполненный quote-кеш сохраняется после CRUD, даже после удаления соответствующего правила, до сброса.
- POST /tariffs/cache/reset очищает только tariff-quotes namespace. Плановый сброс — ежедневно 00:00 Europe/Moscow. Независимого суточного TTL нет. Redis недоступен: quote рассчитывается через БД; reset возвращает 503, а не ложное подтверждение.
- Ключ quote-кеша использует productType/cityId/currency и нормализованный purchasePrice. Для одной группы запроса могут встречаться разные версии quote до сброса; версия возвращается и сохраняется при оприходовании.

### Приёмка и повтор pricing

- GET /stores/{storeId}/deliveries/{deliveryId} возвращает WAITING_PRICING, POSTED или REJECTED.
- WAITING_PRICING содержит nextAttemptAt, attemptCount и при ошибке lastError. Ошибки TARIFFS/отсутствие тарифа остаются в ожидании; worker повторяет автоматически с сохраняемым bounded backoff. Попытки продолжаются после рестарта и после исправления правил.
- POSTED содержит postedAt и рассчитанные поля всех items. Состояние не гарантирует появление товара в STORE: нужно дождаться прихода.
- REJECTED содержит lastError и rejectedPayload с исходным разобранным payload. Некорректные строки не маскируются как корректные DeliveryViewLine; items может быть пустым. Автоматических повторов REJECTED нет.
- POST .../retry-pricing только ускоряет следующую попытку WAITING_PRICING; ответ 202 не означает завершение расчёта. Для POSTED/REJECTED — 409 DELIVERY_NOT_WAITING_PRICING.
- POST /technical/deliveries — учебный эмулятор поставщика. Тело — целый DeliveryReceived; 202 выдаётся после подтверждения Kafka, но не означает приёмку. При неопределённом результате отправки повтор использует прежние eventId/deliveryId. Реальная логистика/тест может публиковать Kafka напрямую.
- Неразобранный JSON и неизвестная версия сохраняются отдельно с координатами Kafka; DeliveryResponse для них не изобретается. Ошибка записи результата/диагностики не является успешной обработкой.

### Каталог и корзина

- GET /stores/{storeId}/catalog возвращает одну позицию продукта, включая quantity=0. stockItemId стабилен при пополнении.
- POST /stores/{storeId}/carts без body создаёт OPEN с version=0. CartId обеспечивает независимость покупателей; имя/ID потока выполнения не используется как владелец корзины. Без авторизации знание cartId даёт доступ к учебной корзине.
- GET корзины показывает состав, version, состояние и сумму. OPEN рассчитывается по текущим ценам STORE, SUBMITTED показывает принятый snapshot.
- PUT .../items/{stockItemId} задаёт итоговое quantity, а не добавляет к нему; body включает expectedCartVersion. DELETE принимает expectedCartVersion в query. Каждый успешный изменяющий запрос увеличивает version на 1, даже PUT прежнего quantity.
- При отсутствии удаляемой строки DELETE возвращает 404 без изменения version. Устаревшая версия — 409 CART_VERSION_CONFLICT. Закрытая корзина не редактируется.
- Добавление сравнивает итоговое количество своей позиции с текущим availableQuantity. Корзина ничего не резервирует; обе независимые корзины могут содержать последнюю единицу. Приход новой цены не меняет version состава OPEN-корзины.

### Submit и восстановление ответа

POST /stores/{storeId}/carts/{cartId}/submit принимает только expectedCartVersion и заголовок Idempotency-Key (1–128 символов латиницы/цифр/`._:-`). Рекомендуемый клиентский ключ — UUID. Тело не принимает сумму, цены или состав от клиента.

Scope уникальности: `(storeId, Idempotency-Key)`. Fingerprint запроса: storeId, cartId и expectedCartVersion, с нормализованным числовым version. Повтор с прежними параметрами возвращает существующий submission до проверки текущего состояния корзины. Иной fingerprint — 409 IDEMPOTENCY_KEY_REUSED. Один ключ в разных магазинах допустим.

В транзакции блокируются корзина и inventory в одном стабильном порядке. Цены/остатки повторно проверяются, состав и сумма фиксируются в snapshot. Списание, расходные движения, submission/outbox и закрытие корзины атомарны. Нехватка одной позиции отклоняет всё; никакой частичной покупки. Submission уникален также по cartId.

После commit ответ всегда 202 с SubmissionResponse. Даже если sender успел опубликовать сообщение, код остаётся 202; publicationStatus может быть PENDING или PUBLISHED. Повтор возвращает тот же идентификатор и актуальное состояние. Новая отправка закрытой корзины с другим ключом — 409 CART_ALREADY_SUBMITTED.

HTML сохраняет ключ и первоначальный expectedCartVersion до установления результата. При сетевой ошибке или reload повторяет их; нельзя подменять version новым значением закрытой корзины. Ошибка/503 с неопределённым исходом commit не доказывает rollback. GET /stores/{storeId}/submissions/{submissionId} восстанавливает известную операцию; если submissionId не получен, нужен повтор submit прежним ключом.

PENDING означает сохранённое списание/outbox, но отсутствие записанной отметки подтверждения. Kafka могла уже принять событие. PUBLISHED требует publishedAt и означает broker acknowledgement, а не оплату. Ошибки sender сохраняют PENDING; после восстановления он отправляет автоматически, без нового расхода и без автоматического возврата товара по тайм-ауту.

## Ошибки

ErrorResponse: timestamp, status, code, message, path; details необязателен и содержит field/message. Не раскрывать stack traces, credentials и внутренний SQL.

| HTTP | Коды/поведение |
| --- | --- |
| 400 | VALIDATION_ERROR: формат, значения, пустая корзина |
| 404 | NOT_FOUND в scope магазина; TARIFF_NOT_FOUND для quote |
| 409 | INSUFFICIENT_STOCK, CART_VERSION_CONFLICT, CART_ALREADY_SUBMITTED, IDEMPOTENCY_KEY_REUSED, TARIFF_AMBIGUOUS, DELIVERY_CONTENT_CONFLICT, DELIVERY_NOT_WAITING_PRICING |
| 503 | DEPENDENCY_UNAVAILABLE; для изменяющих запросов учитывать неопределённый исход |
| 500 | INTERNAL_ERROR; неизвестная внутренняя ошибка |

Kafka-сообщение HTTP-ошибкой не отвечает: причина записывается в диагностику/состояние. Противоречивый новый GoodsPosted не изменяет inventory; нужны сохраняемый конфликт и диагностика.

## Что схема не доказывает

JSON Schema проверяет форму, типы, обязательные поля и допустимые диапазоны, но не исполняет BigDecimal-формулу, sum(items), lowerBound < upperBound, уникальность lineId/productId, соответствие occurredAt бизнес-времени, store ownership, выбор последней цены или блокировки.

Эти инварианты проверяются service integration/E2E в последующих задачах; не добавлять собственный JSON-validator для имитации их выполнения в задаче 1. Получатель валидирует JSON, затем бизнес-содержимое и атомарно сохраняет результат. REJECTED намеренно может показывать raw payload, не проходящий схему корректной поставки.

## Доступность по задачам

| Задача | Доступность после реализации |
| --- | --- |
| 1 | Схемы, OpenAPI, примеры и contract-tests; новые endpoints ещё недоступны |
| 2 | Три приложения и миграционная/локальная среда; бизнес-потоки ещё ограничены |
| 3 | Quote, CRUD правил, reset/fallback TARIFFS |
| 4 | Приёмка WAREHOUSE, автоматический pricing, GoodsPosted outbox и техническое API |
| 5 | Приход/каталог STORE и независимые versioned корзины |
| 6 | Submit, submission status и OrderSubmitted outbox |
| 7 | HTML на новом API |
| 8 | Полные E2E и CI; статусы CASES соответствуют выполненным проверкам |
