# Ключевые сквозные сценарии

Статус: спецификация целевого поведения. Задачи 1–3 проверили контракты, runtime и TARIFFS. Задача 4 реализует и проверяет WAREHOUSE: приёмку Kafka, pricing, автоматическое восстановление и GoodsPosted outbox. Покрытые части перечислены ниже. Применение GoodsPosted в STORE, новые корзины и оформление заказа ещё не реализованы; полный сквозной сценарий не объявляется выполненным по успеху отдельного сервиса.

Согласованные решения: `docs/implementation-plan.md`. Правила реализации: `AGENTS.md`.

## Как работает новая версия

1. Поставщик/тест публикует DeliveryReceived для магазина в logistics.deliveries.
2. WAREHOUSE сохраняет поставку, время первой приёмки и неизменяемый deliverySequence. Получает quote от TARIFFS и рассчитывает продажную цену. При проблемах поставка сохраняется в WAITING_PRICING и автоматически повторяется позже.
3. TARIFFS выбирает правило по типу, закупочной цене, валюте и городу. Redis хранит производный кеш результата; при отказе Redis доступная БД позволяет продолжить расчёт.
4. WAREHOUSE атомарно сохраняет POSTED и GoodsPosted в outbox, затем публикует событие в warehouse.goods-posted. POSTED ещё не гарантирует появление товара в STORE.
5. STORE атомарно применяет событие и приход. Для каждого продукта магазина существует одна inventory-позиция. Количество увеличивается один раз; цена всего остатка обновляется только более новой поставкой по deliverySequence.
6. Покупатель создаёт отдельную корзину и добавляет товары в пределах текущего остатка. Корзина не резервирует и не уменьшает количество. Чужие корзины не участвуют в проверке доступности.
7. Submit с Idempotency-Key и expectedCartVersion повторно проверяет количество. STORE атомарно списывает товар, записывает расходы/submission/outbox и закрывает корзину. Ответ 202 означает принятие к отправке.
8. STORE sender публикует OrderSubmitted в store.order-submitted и после подтверждения отмечает PUBLISHED. Повтор публикации сохраняет идентификатор и не списывает товар повторно. Оплата и дальнейшее исполнение отсутствуют.

## Общие условия тестирования

- Для сценариев создавать независимые storeId/cartId/deliveryId и ключи операций. Не делить изменяемые корзины между параллельными тестами.
- Для проверки асинхронности ожидать конкретное состояние с ограничением времени. Случайные долгие sleep не использовать.
- Для конкуренции синхронно запускать операции через barrier/latch и проверять результаты обеих попыток.
- Для сбоев использовать тестовый профиль: контролируемые HTTP-ответы, паузу sender и остановку после обозначенной границы. Проверки рестарта сохраняют PostgreSQL.
- Проверять HTTP/Kafka и инварианты БД. Прямая запись fixtures в БД допустима для узкой проверки компонента, но не заменяет сквозную поставку.
- Глобальный reset тарифного кеша проверять в изолированной среде или без конкурентных кейсов, затрагивающих тот же namespace.
- CASES-12 включает отдельные проверки каждого диапазона, ошибки неоднозначности и округления; не объединять разные исходы в один тест с условными assertions.

## Сценарии

Для всех записей ниже первоначальный статус: **запланирован**. Выполненные части перечислены в разделе покрытия; они не означают выполнение всего E2E. Номера задач соответствуют implementation-plan.md.

Точные JSON/HTTP-форматы — в [контрактах v1](contracts/README.md). Для PUT/DELETE состава клиент передаёт expectedCartVersion; POSTED и PUBLISHED требуют своих timestamps. Отображаемое имя/описание inventory меняется вместе с ценой только более новым deliverySequence. SUBMITTED-корзина показывает принятый snapshot.

| ID | Исходные данные и действие | Ожидаемый результат | Задачи |
| --- | --- | --- | --- |
| CASES-01 | S-1 получает 10 P-1 по 100.00; тариф 0.20. Пройти поставку через Kafka, WAREHOUSE, TARIFFS и STORE. | Одна inventory-позиция: quantity=10, salePrice=120.00; POSTED и приход; сохранены receivedAt/postedAt, ruleId/version. | 3, 4, 5, 8 |
| CASES-02 | Дважды отправить одинаковую DeliveryReceived с прежним deliveryId, включая конкурентный повтор. | Одна приёмка, один логический GoodsPosted и один приход; quantity=10; sequence не меняется. | 4, 8 |
| CASES-03 | Повторить GoodsPosted с тем же eventId; отдельно отправить тот же результат поставки с новым eventId. | Ни одна форма повтора не увеличивает количество и не создаёт второй приход. | 5, 8 |
| CASES-04 | Изменить quantity/price/items при прежнем storeId+deliveryId; проверить на входе WAREHOUSE и на входе STORE. | Конфликт диагностируется; ранее принятые данные и баланс не перезаписываются. Каждое направление — отдельный тест. | 4, 5, 8 |
| CASES-05 | Передать повреждённый JSON, неизвестную schemaVersion и отдельно недопустимое количество. | JSON/версия получают сохраняемую диагностику с Kafka coordinates; разобранная невалидная поставка — REJECTED. Ошибка сохранения не считается успешной обработкой. | 1, 4, 8 |
| CASES-06 | TARIFFS недоступен при приёмке; затем восстановить его, не вызывая ручной retry. | WAITING_PRICING с причиной/попытками; до восстановления нет прихода; автоматический worker завершает поставку и создаёт один приход. | 4, 8 |
| CASES-07 | Остановить WAREHOUSE после сохранения WAITING_PRICING и запустить с той же БД. | Поставка автоматически продолжает обработку; receivedAt/deliverySequence не меняются; один GoodsPosted. | 4, 8 |
| CASES-08 | На остатке 6 P-1 по 120.00; получить новую поставку 10 P-1 с salePrice=144.00. | Одна прежняя inventory-позиция с прежним stockItemId: quantity=16 и цена всех 16 единиц 144.00. | 5, 8 |
| CASES-09 | Поставка А(sequence=1, price=120.00) задержана; Б(sequence=2, price=144.00) применена раньше. Затем применить А. | Количество включает обе уникальные поставки, цена остаётся 144.00. Повтор Б не меняет результат. | 4, 5, 8 |
| CASES-10 | Запросить quote дважды; изменить соответствующее правило через CRUD без reset; запросить снова. | Первый запрос использует БД и заполняет кеш; следующие используют сохранённый quote. CRUD сам по себе не очищает его. | 3, 8 |
| CASES-11 | После CASES-10 выполнить ручной reset; отдельно вызвать плановый reset для 00:00 Europe/Moscow. | Следующий quote читает новое правило. Другие Redis namespaces не очищаются. Сам reset не меняет inventory-цены; новая поставка может их изменить. | 3, 8 |
| CASES-12 | Проверить lower, upper и соседние значения тарифных диапазонов; отсутствие/пересечение правил; расчёт дробной итоговой цены. | Ровно одно правило для [lower, upper); NOT_FOUND/AMBIGUOUS не превращаются в нулевую наценку; HALF_UP до двух знаков. | 3, 4, 8 |
| CASES-13 | Redis недоступен, PostgreSQL TARIFFS доступен; затем отдельно проверить отсутствие кеша и недоступность БД. | В первом случае корректный quote без кеша; во втором ошибка и ожидание pricing. | 3, 4, 8 |
| CASES-14 | Остаток=5. А и Б независимо добавляют по 5 в разные корзины; А пытается увеличить свою позицию до 6. | Обе корзины с 5 допустимы, остаток=5; увеличение А до 6 отклоняется без изменения корзины/версии. | 5, 8 |
| CASES-15 | Конкурентно изменять одну корзину и читать её version. | Принятые изменения согласованы с версией; нет потерянного обновления, повреждённого состава или обхода quantity limit. | 5, 8 |
| CASES-16 | Остаток=10 по 120.00, в корзине 3. Выполнить submit с актуальной version. | HTTP 202; остаток=7; расход=3; сумма=360.00; один submission/outbox; корзина SUBMITTED. После Kafka ack — PUBLISHED, остаток по-прежнему 7. | 6, 8 |
| CASES-17 | Корзина содержит две позиции, одной после чужой покупки недостаточно. Выполнить submit. | 409 INSUFFICIENT_STOCK; нет частичного списания, расходов и outbox; корзина OPEN. | 6, 8 |
| CASES-18 | Повторить принятый submit с прежним ключом и параметрами; отдельно отправить два таких запроса одновременно. | Один submissionId, один расход; повтор возвращает существующий результат. Для конкурентного конфликта корректно завершена неудачная транзакция. | 6, 8 |
| CASES-19 | Использовать прежний ключ с другим cartId или expectedCartVersion. | 409 IDEMPOTENCY_KEY_REUSED; нет нового расхода/outbox. | 6, 8 |
| CASES-20 | Попробовать submit закрытой корзины с новым ключом; отдельно попытаться изменить её состав. | 409 CART_ALREADY_SUBMITTED/соответствующий конфликт закрытого состояния; новый расход отсутствует. | 5, 6, 8 |
| CASES-21 | Две корзины конкурируют за последнюю единицу. Запустить submit одновременно. | Одна принята, другая получает нехватку; остаток=0; один расход на единицу. | 6, 8 |
| CASES-22 | После получения version изменить корзину и отправить submit со старой version. | 409; остатки и outbox не меняются. | 6, 8 |
| CASES-23 | Использовать inventory/cart/submission другого магазина в API S-1. | Данные магазинов не смешиваются; запрос отклонён, чужие остатки/корзина не меняются. | 5, 6, 8 |
| CASES-24 | Вызвать управляемую ошибку после изменения остатка, но до commit submit. | Полный rollback: прежний остаток, OPEN-корзина, нет расхода/submission/outbox. | 6, 8 |
| CASES-25 | Kafka недоступна после commit WAREHOUSE; отдельно после commit STORE submit. Затем восстановить Kafka. | Событие остаётся в PENDING и автоматически публикуется после восстановления. STORE quantity повторно не списывается; WAREHOUSE не рассчитывает новую цену. | 4, 6, 8 |
| CASES-26 | Остановить WAREHOUSE после commit POSTED до publish; отдельно STORE после commit submit до publish. Перезапустить с прежними БД. | Outbox продолжает отправку; одно логическое оприходование/списание. Каждое окно — отдельный тест. | 4, 6, 8 |
| CASES-27 | Kafka приняла событие, но отметка PUBLISHED потеряна. Выполнить повтор sender. | Повтор содержит прежние eventId и payload; допустимы две физические Kafka-записи, но один приход/расход и один submissionId. | 4, 5, 6, 8 |
| CASES-28 | Добавить продукт по 120.00; новой поставкой изменить текущую цену на 144.00; выполнить submit. После commit изменить цену ещё раз. | Submit использует 144.00; сохранённый payload/сумма принятой заявки после следующих поставок неизменны. | 5, 6, 7, 8 |
| CASES-29 | STORE принял submit, но browser не получил ответ. Перезагрузить страницу и повторить сохранённый запрос. | HTML использует прежний ключ и параметры; получает исходный submissionId; нового расхода нет. | 6, 7, 8 |
| CASES-30 | Использовать одинаковую строку Idempotency-Key для разных корзин в S-1 и S-2. | Две независимые допустимые операции; scopes ключей разделены по storeId. | 6, 8 |
| CASES-31 | Открыть магазин в двух независимых browser contexts. Создать/изменить корзины и оформить одну. | HTML работает с новым API; состав корзин независим; второй покупатель получает актуальный результат проверки остатка. | 7, 8 |
| CASES-32 | Передать поставку с двумя строками одного productId. | REJECTED с понятной причиной; нет неоднозначного выбора цены и прихода. | 1, 4, 8 |
| CASES-33 | Pricing ждёт отсутствующее правило; добавить корректное правило, обеспечить нужный reset и дождаться следующей автоматической попытки. | Поставка завершена без ручного retry-pricing; один приход, зафиксированы использованные ruleId/version. | 3, 4, 8 |
| CASES-34 | Автоматическая попытка pricing пересекается с диагностическим ручным retry той же поставки. | Один POSTED и один логический GoodsPosted; цены, идентификаторы и sequence завершённого результата не перезаписываются. | 4, 8 |

## Сквозные инварианты

1. Inventory quantity равен сумме уникальных приходов минус сумма принятых расходов и не отрицателен.
2. Для `(storeId, productId)` существует одна inventory-позиция.
3. Цена inventory соответствует максимальному deliverySequence среди применённых приходов этого продукта.
4. Непринятый submit не меняет остатки, корзину или outbox.
5. Каждый принятый расход связан с submission и сохранённым исходящим сообщением.
6. Повтор поставки, прихода или принятого HTTP-запроса не создаёт новое движение.
7. PUBLISHED означает подтверждение брокера; PENDING допускает уже выполненную физическую публикацию без сохранённой отметки.
8. Автоматические повторы не требуют сохранения потока выполнения и продолжаются после перезапуска.
9. Принятый OrderSubmitted содержит неизменяемый snapshot цен и состава.

## Обновление покрытия

### Задача 1: только структурные контракты

Команда: `mvn -pl contract-tests test` (Java 21). Проверено 3 октября 2026 года: 62 теста, 0 ошибок, 0 падений, 0 пропусков; Spring и инфраструктура не запускались.

| Проверяемая часть | Тестовый файл/метод | Связанные CASES и границы проверки |
| --- | --- | --- |
| Примеры событий и HTTP | `contract-tests/src/test/java/com/shop/contracts/HttpContractTest.java`, `publishedExamplesMatchCanonicalDefinitions` | Форматы CASES-01/05/16/25; не обработка/доставка/транзакции |
| Decimal strings и DTO round trip | `contract-tests/src/test/java/com/shop/contracts/EventContractTest.java`, `supplierDtoPreservesDecimalStringAndProductMetadata`, `preservesPriceBeyondFloatingPointIntegerPrecision`, `eventEnvelopeRoundTripPreservesWireContract` | Формат CASES-01/12/28; не расчёт тарифа или цены STORE |
| Повреждённый JSON/версия/тип/UUID/UTC | `EventContractTest`, `rejectsMalformedOrAmbiguousJson`, `rejectsUnknownSchemaVersion`, `rejectsWrongEventType`, `rejectsMalformedEventIdentifier`, `rejectsInvalidOrNonUtcTimestamp` | Структурная часть CASES-05; не сохраняемая Kafka-диагностика |
| Snapshot shape, версии и states | `HttpContractTest`, `rejectsClientSuppliedSubmitTotal`, `rejectsSubmitWithoutExpectedVersion`, `publishedSubmissionRequiresTimestamp`, `pendingSubmissionCannotClaimPublishedTimestamp`, `submittedCartRequiresSubmissionIdentifier` | Формат CASES-16/18/22/28; не atomic commit/дедупликация |
| Отклонённая поставка | `HttpContractTest`, `rejectedDeliveryCanExposeInvalidOriginalQuantity`, `rejectedDeliveryRequiresOriginalPayload` | Представление CASES-05/32; не бизнес-валидация уникальности продуктов |
| OpenAPI и внешние schema refs | `HttpContractTest`, `openApiDocumentParsesWithoutDiagnostics`, `openApiReferencesResolveToCanonicalDefinitions`, `submitDocumentsRequiredKeyAndAcceptedResponse` | Спецификация целевого API; ни один новый endpoint не считается доступным |

После реализации каждого сценария добавлять отдельную запись покрытия: ID → абсолютный/репозиторный путь тестового файла, имя метода, уровень проверки, команда запуска и результат последнего проверенного запуска. Несколько уровней для одного сценария допустимы; module integration и сквозной E2E обозначать явно. Не ставить «пройден» по наличию теста без запуска.

## Проверки runtime задачи 2

Исторический результат PR #38. `TariffServiceCacheTest` существовал в этом PR; в задаче 3 его заменил `TariffApiTest`, искусственные задержки и legacy cache удалены.

Это подготовка окружения, а не выполнение CASES-01–34. WAREHOUSE пока не обрабатывает поставки; legacy STORE не списывает остаток при оформлении.

Команда: `mvn -B -ntp test` на Java 21 с работающим Docker. Проверенный запуск 3 октября 2026: 78 тестов, 0 failures/errors/skipped. Из них 62 контрактных, 7 legacy unit и 9 module integration/runtime.

| Проверка | Файл и метод | Уровень / инвариант |
| --- | --- | --- |
| Чистая и повторная STORE-миграция | `store-service/src/test/java/stageTests/StoreRuntimeTest.java`, `migrationIsRepeatableWithoutDestroyingData` | PostgreSQL: V1 применяется один раз, сохранённый order не удаляется |
| Изоляция ролей | тот же файл, `applicationRolesCannotConnectToOtherServiceDatabases` | Реальный bootstrap SQL: своя БД доступна, чужие сервисные и служебные БД отклоняют соединение |
| STORE health | тот же файл, `healthIsPublicAndDatabaseIsUp` | Полный Spring HTTP + PostgreSQL, health доступен без Basic auth |
| Совместимость перенесённого legacy потока | `store-service/src/test/java/stageTests/ProductFlowTest.java`, `productAppearsInListAndCart` | Реальные Kafka/PostgreSQL + WireMock, появление товара и запрет добавления сверх остатка; не независимые корзины |
| TARIFFS схема и health | `tariffs-service/src/test/java/com/tariffs/TariffServiceCacheTest.java`, `migrationsAndHealthAreReady` | PostgreSQL/Redis: семь legacy fixtures, повторная миграция не выполняет SQL, health UP |
| Старый кеш | тот же файл, `firstCallHitsDbSecondUsesCache`, `cacheResetForcesNextCallToHitDb` | Legacy list/cache/reset с прежними задержками; не новый quote CASES-10–13 |
| WAREHOUSE fixtures | `warehouse-service/src/test/java/com/shop/warehouse/WarehouseRuntimeTest.java`, `fixturesSurviveRepeatedMigration` | PostgreSQL: S-1/MOSCOW и S-2/SPB, V1/V2 применены один раз |
| WAREHOUSE health | тот же файл, `healthIsUp` | HTTP runtime с реальной БД; не подтверждение приёмки |

Infrastructure-only запуск: `docker compose up -d --wait`; все три зависимости healthy. [CI run 37148872985](https://github.com/golenev/store-app-microservices/actions/runs/37148872985) для code commit `8d0cd98`: reactor и полный source-build Compose smoke прошли. После `up --force-recreate --no-build --no-deps --wait` трёх приложений сохранён order, тарифов 7, магазинов 2. Локально те же инварианты подтверждены с предварительно собранными JAR; три HTTP health вернули UP. Это runtime/restart smoke, не сквозная бизнес-поставка. Существующие volumes не удалялись.

## Проверки тарифного API задачи 3

Исторический результат задачи 3. Отмеченные здесь будущими WAREHOUSE pricing/HALF_UP проверены в следующем разделе; применение цены к STORE остаётся будущим.

Уровень: module integration. Файл: `tariffs-service/src/test/java/com/tariffs/TariffApiTest.java`. Каждый HTTP-запрос проходит через полный Spring runtime; PostgreSQL 16 и Redis 7 запускаются Testcontainers. Отказы воспроизводятся паузой настоящего контейнера. Правила тестовых городов изолированы; проверки глобального reset выполняются последовательно.

Команда: `mvn -B -ntp test` (Java 21, Docker). Проверенный локальный запуск 3 октября 2026: **138 тестов, 0 failures/errors/skipped** — контракты 62, STORE 11, TARIFFS 63, WAREHOUSE 2. После настройки readiness повторно выполнено `mvn -B -ntp -pl tariffs-service test`: **63 теста, 0 failures/errors/skipped**. Сборка всех модулей `mvn -B -ntp -DskipTests package` также прошла; локально использован offline Maven settings для уже загруженных зависимостей.

| CASES / проверка | Методы TariffApiTest | Проверенный результат и границы |
| --- | --- | --- |
| CASES-10 | `repeatedQuoteUsesCacheWithoutDatabaseReadOrTtl`, `updateKeepsCachedSnapshotUntilManualReset`, `deletionKeepsSnapshotUntilReset` | Повтор не читает правила из БД. PUT/DELETE не очищают snapshot; TTL отсутствует. Новый запрос цены может получить новую версию до reset. |
| CASES-11 | `resetDoesNotFlushOtherRedisNamespaces`, `scheduledResetUsesMoscowMidnightAndTheSameNamespace`, `inFlightOldCalculationCannotRefillAfterReset`, `lostGenerationDoesNotRevalidateOldFillToken` | Ручной и плановый reset очищают только quote entries; cron настроен на полночь Москвы. Отложенный расчёт со старым token не заполняет кеш после reset или потери epoch. Плановый метод вызывается управляемо, ожидание реальной полуночи не тестируется. Inventory ещё не реализован. |
| CASES-12: тарифная часть | `fixtureBoundaries`, `missingRuleIsNotCachedAsZeroRate`, `overlappingRulesNeverSelectAnArbitraryWinner`, `fractionalRateIsExactWithoutDoubleOrPrematureRounding` | Границы [lower, upper), 14 fixtures двух городов, ошибки 404/409, точный decimal rate. HALF_UP и продажная цена относятся к WAREHOUSE задачи 4 и ещё не проверены. |
| CASES-13: тарифная часть | `redisOutageFallsBackToDatabaseAndResetReturns503`, `databaseOutageOnCacheMissReturns503`, `cachedQuoteSurvivesDatabaseOutage` | Без Redis quote вычисляется через БД, reset возвращает 503, readiness остаётся 200. Без БД cache miss возвращает 503, cache hit работает. WAITING_PRICING относится к задаче 4. |
| CRUD / конкуренция | `crudUsesVersionedFullReplacementAndExplicitNullUpperBound`, `concurrentUpdatesIncrementVersionTwice`, `creationRespectsTheCatalogLimit`, `concurrentCreatesCannotExceedCatalogLimit` | UUID, версия 1 и атомарное увеличение, явный nullable upperBound, лимит 1000 правил даже при конкурентном создании последнего места. |
| Валидация / изоляция | `invalidQuotePrices`, `invalidDimensionsAndIdentifiers`, `invalidRuleValues`, `malformedRuleJson`, `invalidReplacementDoesNotChangeTheRule`, `databaseRejectsInvalidBounds`, `cityAndProductTypeAreSeparateCacheDimensions`, `malformedCacheEntryIsRecomputed` | Ошибочные деньги, диапазоны, UUID, типы, неизвестные/повторные поля отклоняются; invalid PUT не меняет правило; ключ включает город, тип, валюту и цену. Повреждённый JSON кеша пересчитывается. |
| Миграции / совместимость | `migrationsPreserveFixturesAndHealth`, `legacyStoreEndpointsRemainOperationalWithoutDelayOrLegacyCache`, `routingErrorsDoNotBecomeServerErrors` | V1–V4 повторно не меняют данные; семь legacy тарифов доступны старому STORE без искусственного sleep. Legacy таблица и новые правила независимы до задачи 5. |

CI Compose smoke дополнен HTTP-сценарием: создать правило → quote v1 → PUT v2 → получить прежний cached quote → reset → получить quote v2 → удалить правило. После рестарта проверяется сохранность 14 новых и 7 legacy fixtures. Результат удалённого запуска указывается в PR после выполнения; наличие workflow само по себе не считается успешным запуском.

## Проверки приёмки WAREHOUSE задачи 4

Уровень: module integration с настоящими PostgreSQL 16/Kafka 7.6 и HTTP WireMock вместо TARIFFS. `DeliveryIntegrationTest` запускает полный HTTP/consumer runtime; worker ticks в этом классе вызываются управляемо. `WarehouseRecoveryTest` создаёт и закрывает независимые Spring application contexts с сохранённой контейнерной БД, затем проверяет настоящий scheduler. Это рестарт приложения, а не имитация состояния одним Mockito вызовом.

Команда: `mvn -B -ntp test` (Java 21, Docker). Проверенный локальный запуск 4 октября 2026: **202 теста, 0 failures/errors/skipped** — контракты 62, STORE 11, TARIFFS 63, WAREHOUSE 66. После замены неиспользуемого ORM WAREHOUSE на JDBC и исправления ожидания lastError выполнено `mvn -B -ntp -pl warehouse-service test`: **66 тестов, 0 failures/errors/skipped**. Контейнеры одноразовые, пользовательские volumes не удаляются.

Файлы: `warehouse-service/src/test/java/com/shop/warehouse/DeliveryIntegrationTest.java`, `WarehouseRecoveryTest.java` и `WarehouseRuntimeTest.java` в том же каталоге.

| CASES / проверка | Класс и методы | Проверенный результат и границы |
| --- | --- | --- |
| CASES-01: приёмка и отправка | DeliveryIntegrationTest: `technicalPublishReachesReceptionPricingAndKafka` | HTTP 202 после broker ack → реальная Kafka-приёмка → сохранённый WAITING → HALF_UP цена 120.00 → один outbox → реальный GoodsPosted, key/store и timestamps. Приход в STORE ещё не реализован. |
| CASES-02 | DeliveryIntegrationTest: `reorderedDeliveryWithNewEventDoesNotCreateAnotherAcceptance`, `concurrentReceptionHasOneDeliveryAndSequence` | Повтор eventId, новый транспортный envelope и обратный порядок строк сохраняют один результат/sequence/receivedAt; конкурентный повтор не создаёт вторую поставку. Дедупликация STORE относится к задаче 5. |
| CASES-04: WAREHOUSE | DeliveryIntegrationTest: `changedDeliveryContentIsDiagnosed`, `eventIdentifierCannotMoveBetweenStores` | Изменение quantity/price/name/description/type/productId/lineId под прежним deliveryId и смена магазина при прежнем eventId дают сохраняемый конфликт; принятое содержимое неизменно. Вход STORE ещё не проверен. |
| CASES-05 | DeliveryIntegrationTest: `invalidPayloadIsPersistedRejected`, `invalidEnvelopeIsDiagnosed`, `routingAndUnknownStoreAreDiagnosed`, `diagnosticStorageFailureDoesNotReturnSuccess`, `kafkaOffsetWaitsForDurableDiagnostic` | Invalid payload сохраняет REJECTED/raw payload, без item rows/outbox/retry. Битый JSON, версия, duplicate/unknown fields, UTC/UUID, пустой документ и tombstone диагностируются по Kafka coordinates. Реальный consumer offset не продвигается при ошибке хранения и продвигается после recovery. |
| CASES-06/33: pricing | WarehouseRecoveryTest: `missingRuleRecoversWithAutomaticPersistedBackoff`; DeliveryIntegrationTest: `missingRuleCanRecoverWithoutChangingAcceptance` | После 404 worker сам повторяет и завершает поставку при появлении правила, без ручного retry. attemptCount/backoff сохраняются; cityId определяется fixture магазина. Полный приход STORE остаётся задачей 5/8. |
| CASES-07/26: WAREHOUSE restart | WarehouseRecoveryTest: `restartAutomaticallyRecoversPricingLeaseAndPendingOutbox` | Два отдельных запуска приложения с прежней БД: истёкшая pricing lease восстанавливается, POSTED PENDING публикуется; sequence/receivedAt и сохранённый event payload неизменны. Истечение lease задаётся SQL для управляемого crash window; prod lease — 30 s. STORE restart здесь не проверяется. |
| CASES-12: цена | DeliveryIntegrationTest: `salePriceUsesExactHalfUp`, `salePriceOverflowIsNotSilentlyRoundedOrStored`, `tariffFailureNeverPartiallyPosts`, `secondLineFailureDoesNotPersistFirstLinePricing` | Полкопейки 0.055 → 0.06, шесть знаков rate, overflow без обрезания. Timeout/503/ambiguity/invalid quote не сохраняют частичные строки и outbox. Границы правил покрыты TARIFFS задачей 3. |
| CASES-25: WAREHOUSE sender | DeliveryIntegrationTest: `kafkaOutageKeepsOutboxPendingThenRecovers`, `pendingEventIsIndependentOfPricingAfterCommit` | Реальная pause/unpause Kafka сохраняет PENDING и попытку, затем публикует без пересчёта тарифа. STORE sender ещё отсутствует. |
| CASES-27: WAREHOUSE ack window | DeliveryIntegrationTest: `lostPublishedMarkReplaysSameEventAndPayload` | После настоящего broker ack тестовый spy прерывает запись отметки. Повтор даёт две идентичные физические Kafka-записи и один outbox, прежние eventId/key/payload. Приход/расход STORE пока не проверен. |
| CASES-32 | DeliveryIntegrationTest: `invalidPayloadIsPersistedRejected` | Повтор productId/lineId внутри поставки сохраняет REJECTED с исходными строками, без расчёта и прихода. |
| CASES-34 | DeliveryIntegrationTest: `manualRetryAndReclaimedWorkerCannotPostTwice`, `concurrentPricingClaimsHaveOneOwner` | Retry не сбрасывает активный token; одна claim из двух конкурирующих. Reclaim блокирует renew/post/failure старого worker; новый завершает ровно один outbox. |
| Atomic result/outbox | DeliveryIntegrationTest: `outboxFailureRollsBackPostedAndLinePrices` | Настоящий SQL trigger падает на INSERT outbox после обновления строк: вся транзакция откатывается, последующая попытка может завершиться. Trigger существует только в тестовой БД. |
| Лимиты / scope / миграции | DeliveryIntegrationTest: `thousandLineDeliveryTraversesKafkaWithoutTruncation`, `exhaustedStoreSequenceIsDiagnosedWithoutOverflow`, `statusApiValidatesScopeAndErrors`; WarehouseRuntimeTest: `fixturesSurviveRepeatedMigration`, `healthIsUp` | Более 1 MB / 1000 строк проходит через Kafka без усечения, 1001 отклоняется; sequence не переполняется; GET не смешивает магазины; ошибки имеют безопасный UTC формат; V1–V3 повторно не меняют fixtures. |

CI Compose smoke дополнен настоящей цепочкой технический publisher → Kafka → WAREHOUSE → TARIFFS → POSTED/outbox PUBLISHED. Повторяется исходное событие, затем приложения пересоздаются с прежней БД; GET и SQL проверяют один результат/outbox и прежнюю цену. Результат удалённого source-build прогона указывается в PR после выполнения. Это проверка поставки до границы WAREHOUSE, не полный CASES-01 с inventory STORE.
