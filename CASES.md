# Ключевые сквозные сценарии

## Регрессия разделения backend по слоям

После переноса STORE, WAREHOUSE и TARIFFS по слоям сценарии ниже сохраняют внешний контракт. HTTP-контроллеры обращаются к сервисам; SQL выполняют репозитории. Приход, идемпотентное оформление, блокировки остатков, WAITING_PRICING и outbox сохраняют прежние транзакционные границы. Приём Kafka и публикация вынесены из конфигурации и HTTP-контроллера в отдельные компоненты.

Проверки: `python scripts/check-backend-layers.py` и `mvn clean verify` на Java 21 с Docker. Существующие API↔PostgreSQL тесты создают настоящие сервисы с новыми репозиториями; подготовка данных и независимые SQL-проверки сохранены. Maven не подтверждает работу брокера или полный браузерный поток: для них остаётся отдельный Kotlin E2E-прогон. Структура и детали транзакций описаны в [backend-architecture.md](docs/backend-architecture.md).

Локально 10 октября 2026: Maven **133/133**, без ошибок и пропусков; в том числе STORE API↔PostgreSQL 14/14, TARIFFS 12/12, WAREHOUSE 9/9 и контракты 62/62. Структурная проверка прошла для 81 production Java-файла. Kotlin E2E повторно не запускался.

При последующей редакции Javadoc описания сервисных Java-тестов приведены к схеме «исходные данные → действие → проверяемый результат». Их код, данные и идентификаторы сценариев сохранены. Компиляция и проверка Javadoc прошли; это проверка документации, а не новый запуск сценариев или подтверждение дополнительного покрытия.


Статус: задачи 1–8 включены в master. Учебный рефакторинг заменяет прежние сервисные и серверные Kotlin проверки; контрактный модуль и браузерный E2E-пакет с обвязкой сохранены. Исторические записи ниже описывают состояние соответствующих PR. Актуальное покрытие указано в следующем разделе и в docs/testing-pyramid.md.

Согласованные решения: `docs/implementation-plan.md`. Правила реализации: `AGENTS.md`.

Подготовка окружения приложений описана в [infra/README.md](infra/README.md): SQL создаёт сервисные базы и пользователей при первом запуске Compose с пустым хранилищем. Unit- и контрактные тесты его не используют; Java-проверки с PostgreSQL Testcontainers подготавливают отдельные базы. Это описание условий запуска, а не новая проверка сквозных сценариев.

## Актуальное покрытие после учебного рефакторинга

Сервисные тесты и три серверных Kotlin E2E удалены по запросу пользователя. Контракты, пакет `org.golenev.tests.e2e_tests` и его обвязка не изменены. Новый реестр — [testing-pyramid.md](docs/testing-pyramid.md): девять независимых проверок чистой логики и девять одинаковых CRUD-сценариев тарифных правил на нескольких уровнях. Проверки Kafka не добавляются.

CASES-01–34 остаются требованиями поведения приложения. Их историческое покрытие ниже не означает, что удалённые классы продолжают существовать. Новый CRUD-набор покрывает явно перечисленные операции тарифных правил; не заявляет проверку всего CASES-12, кеша, конкуренции, восстановления pricing или outbox.

| Область | Текущие проверки | Граница результата |
| --- | --- | --- |
| Форматы контрактов | HttpContractTest, EventContractTest | Схемы и тестовые JSON, без сервисов |
| Чистая логика | StorePureLogicTest, WarehousePureLogicTest, TariffPureLogicTest | Отдельные значения; не сквозные CASES |
| CRUD правил тарифа | TariffCrudMockitoTest, TariffCrudWireMockTest, TariffCrudPostgresTest, TariffCrudApiTest, TariffCrudDatabaseApiTest | TAR-CRUD-001–009; полный реестр ID и методов в отдельном документе |
| HTML и оформление | HtmlE2ETest, UiOrderKafkaE2ETest | Сохранённые сценарии; примеры priceSnapshot, lostResponse, versionConflict, supplierRetry и два UI→Kafka пути |
| Ранее удалённые проверки отказов, повторов и гонок | Новых эквивалентных тестов в учебном наборе нет | Исторические результаты не засчитываются как текущее покрытие |

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

Точные JSON/HTTP-форматы — в [контрактах v1](contract-tests/contracts/README.md). Для PUT/DELETE состава клиент передаёт expectedCartVersion; POSTED и PUBLISHED требуют своих timestamps. Отображаемое имя/описание inventory меняется вместе с ценой только более новым deliverySequence. SUBMITTED-корзина показывает принятый snapshot.

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

## История покрытия до учебного рефакторинга

### Задача 1: только структурные контракты

Команда: `mvn -pl contract-tests test` (Java 21). Проверено 3 октября 2026 года: 62 теста, 0 ошибок, 0 падений, 0 пропусков; Spring и инфраструктура не запускались.

| Проверяемая часть | Тестовый файл/метод | Связанные CASES и границы проверки |
| --- | --- | --- |
| Примеры событий и HTTP | `contract-tests/src/test/java/com/shop/contracts/HttpContractTest.java`, 22 явных метода `accepts…Example` с JSON внутри | Форматы CASES-01/05/16/25; не обработка/доставка/транзакции |
| Decimal strings и DTO round trip | `contract-tests/src/test/java/com/shop/contracts/EventContractTest.java`, `supplierDtoPreservesDecimalStringAndProductMetadata`, `preservesPriceBeyondFloatingPointIntegerPrecision`, `deliveryReceivedEnvelopeRoundTripPreservesWireContract`, `goodsPostedEnvelopeRoundTripPreservesWireContract`, `orderSubmittedEnvelopeRoundTripPreservesWireContract` | Формат CASES-01/12/28; не расчёт тарифа или цены STORE |
| Повреждённый JSON/версия/тип/UUID/UTC | `EventContractTest`, `rejectsMalformedOrAmbiguousJson`, `rejectsUnknownSchemaVersion`, `rejectsWrongEventType`, `rejectsMalformedEventIdentifier`, `rejectsInvalidOrNonUtcTimestamp` | Структурная часть CASES-05; не сохраняемая Kafka-диагностика |
| Snapshot shape, версии и states | `HttpContractTest`, `rejectsClientSuppliedSubmitTotal`, `rejectsSubmitWithoutExpectedVersion`, `publishedSubmissionRequiresTimestamp`, `pendingSubmissionCannotClaimPublishedTimestamp`, `submittedCartRequiresSubmissionIdentifier` | Формат CASES-16/18/22/28; не atomic commit/дедупликация |
| Отклонённая поставка | `HttpContractTest`, `rejectedDeliveryCanExposeInvalidOriginalQuantity`, `rejectedDeliveryRequiresOriginalPayload` | Представление CASES-05/32; не бизнес-валидация уникальности продуктов |
| OpenAPI и внешние schema refs | `HttpContractTest`, `openApiDocumentParsesWithoutDiagnostics`, `openApiReferencesResolveToCanonicalDefinitions`, `submitDocumentsRequiredKeyAndAcceptedResponse` | Спецификация целевого API; ни один новый endpoint не считается доступным |

После реализации каждого сценария добавлять отдельную запись покрытия: ID → абсолютный/репозиторный путь тестового файла, имя метода, уровень проверки, команда запуска и результат последнего проверенного запуска. Несколько уровней для одного сценария допустимы; интеграционные проверки модулей и сквозной E2E обозначать явно. Не ставить «пройден» по наличию теста без запуска.

После переноса примеров в тесты каждый сценарий содержит собственный JSON; `examples/manifest.json` не участвует в запуске. Проверки остаются структурными: подготовленные данные сверяются со схемой, без настоящего или имитационного сервера. Документационные файлы `contract-tests/contracts/examples` этим модулем больше не проверяются.

10 октября 2026 каталог контрактов перемещён в `contract-tests/contracts`. Схемы и примеры перенесены без изменения JSON; путь ресурсов Java `/contracts` сохранён, пути документации и CI smoke обновлены. Контрактный прогон на JDK 21: `mvn -o -B -ntp -pl contract-tests test` — **62/62**, без ошибок и пропусков. Эта проверка подтверждает формат и разрешение ссылок OpenAPI; сквозной Compose-прогон и E2E повторно не выполнялись.

Проверка переноса 5 октября 2026 года: `mvn -o -B -ntp -pl contract-tests clean test` на Java 21 с локальным Maven-кешем — **62/62**, без ошибок, падений и пропусков (EventContractTest: 26, HttpContractTest: 36). Чистая сборка копирует только 2 ресурса: схему и OpenAPI; файлы примеров отсутствуют в ресурсах тестов.

## Проверки runtime задачи 2

Это историческое покрытие этапа 2. В задаче 5 legacy ProductFlow/unit-тесты удалены вместе с прежней моделью; актуальные STORE-проверки приведены ниже.

Исторический результат PR #38. `TariffServiceCacheTest` существовал в этом PR; в задаче 3 его заменил `TariffApiTest`, искусственные задержки и legacy cache удалены.

Это подготовка окружения, а не выполнение CASES-01–34. WAREHOUSE пока не обрабатывает поставки; legacy STORE не списывает остаток при оформлении.

Команда: `mvn -B -ntp test` на Java 21 с работающим Docker. Проверенный запуск 3 октября 2026: 78 тестов, без ошибок и пропусков. Из них 62 контрактных, 7 legacy unit и 9 интеграционные проверки модулей/runtime.

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

Уровень: интеграционные проверки модулей. Файл: `tariffs-service/src/test/java/com/tariffs/TariffApiTest.java`. Каждый HTTP-запрос проходит через полный Spring runtime; PostgreSQL 16 и Redis 7 запускаются Testcontainers. Отказы воспроизводятся паузой настоящего контейнера. Правила тестовых городов изолированы; проверки глобального reset выполняются последовательно.

Команда: `mvn -B -ntp test` (Java 21, Docker). Проверенный локальный запуск 3 октября 2026: **138 тестов, без ошибок и пропусков** — контракты 62, STORE 11, TARIFFS 63, WAREHOUSE 2. После настройки readiness повторно выполнено `mvn -B -ntp -pl tariffs-service test`: **63 теста, без ошибок и пропусков**. Сборка всех модулей `mvn -B -ntp -DskipTests package` также прошла; локально использован offline Maven settings для уже загруженных зависимостей.

| CASES / проверка | Методы TariffApiTest | Проверенный результат и границы |
| --- | --- | --- |
| CASES-10 | `repeatedQuoteUsesCacheWithoutDatabaseReadOrTtl`, `updateKeepsCachedSnapshotUntilManualReset`, `deletionKeepsSnapshotUntilReset` | Повтор не читает правила из БД. PUT/DELETE не очищают snapshot; TTL отсутствует. Новый запрос цены может получить новую версию до reset. |
| CASES-11 | `resetDoesNotFlushOtherRedisNamespaces`, `scheduledResetUsesMoscowMidnightAndTheSameNamespace`, `inFlightOldCalculationCannotRefillAfterReset`, `lostGenerationDoesNotRevalidateOldFillToken` | Ручной и плановый reset очищают только quote entries; cron настроен на полночь Москвы. Отложенный расчёт со старым token не заполняет кеш после reset или потери epoch. Плановый метод вызывается управляемо, ожидание реальной полуночи не тестируется. Inventory ещё не реализован. |
| CASES-12: тарифная часть | `fixtureBoundaries`, `missingRuleIsNotCachedAsZeroRate`, `overlappingRulesNeverSelectAnArbitraryWinner`, `fractionalRateIsExactWithoutDoubleOrPrematureRounding` | Границы [lower, upper), 14 fixtures двух городов, ошибки 404/409, точный decimal rate. HALF_UP и продажная цена относятся к WAREHOUSE задачи 4 и ещё не проверены. |
| CASES-13: тарифная часть | `redisOutageFallsBackToDatabaseAndResetReturns503`, `databaseOutageOnCacheMissReturns503`, `cachedQuoteSurvivesDatabaseOutage` | Без Redis quote вычисляется через БД, reset возвращает 503, readiness остаётся 200. Без БД cache miss возвращает 503, cache hit работает. WAITING_PRICING относится к задаче 4. |
| CRUD / конкуренция | `crudUsesVersionedFullReplacementAndExplicitNullUpperBound`, `concurrentUpdatesIncrementVersionTwice`, `creationRespectsTheCatalogLimit`, `concurrentCreatesCannotExceedCatalogLimit` | UUID, версия 1 и атомарное увеличение, явный nullable upperBound, лимит 1000 правил даже при конкурентном создании последнего места. |
| Валидация / изоляция | `invalidQuotePrices`, `invalidDimensionsAndIdentifiers`, `invalidRuleValues`, `malformedRuleJson`, `invalidReplacementDoesNotChangeTheRule`, `databaseRejectsInvalidBounds`, `cityAndProductTypeAreSeparateCacheDimensions`, `malformedCacheEntryIsRecomputed` | Ошибочные деньги, диапазоны, UUID, типы, неизвестные/повторные поля отклоняются; invalid PUT не меняет правило; ключ включает город, тип, валюту и цену. Повреждённый JSON кеша пересчитывается. |
| Миграции / совместимость (история задачи 3) | `migrationsPreserveFixturesAndHealth`, прежний `legacyStoreEndpointsRemainOperationalWithoutDelayOrLegacyCache`, `routingErrorsDoNotBecomeServerErrors` | В задаче 3 семь legacy тарифов были доступны старому STORE. В задаче 5 прежний compatibility-тест заменён на `legacyStoreEndpointsAreRemoved`: старые endpoints возвращают 404, семь исторических DB fixtures сохранены. |

CI Compose smoke дополнен HTTP-сценарием: создать правило → quote v1 → PUT v2 → получить прежний cached quote → reset → получить quote v2 → удалить правило. После рестарта проверяется сохранность 14 новых и 7 legacy fixtures. Результат удалённого запуска указывается в PR после выполнения; наличие workflow само по себе не считается успешным запуском.

## Проверки приёмки WAREHOUSE задачи 4

Уровень: интеграционные проверки модулей с настоящими PostgreSQL 16/Kafka 7.6 и HTTP WireMock вместо TARIFFS. `DeliveryIntegrationTest` запускает полный HTTP/consumer runtime; worker ticks в этом классе вызываются управляемо. `WarehouseRecoveryTest` создаёт и закрывает независимые Spring application contexts с сохранённой контейнерной БД, затем проверяет настоящий scheduler. Это рестарт приложения, а не имитация состояния одним Mockito вызовом.

Команда: `mvn -B -ntp test` (Java 21, Docker). Проверенный локальный запуск 4 октября 2026: **202 теста, без ошибок и пропусков** — контракты 62, STORE 11, TARIFFS 63, WAREHOUSE 66. После замены неиспользуемого ORM WAREHOUSE на JDBC и исправления ожидания lastError выполнено `mvn -B -ntp -pl warehouse-service test`: **66 тестов, без ошибок и пропусков**. Контейнеры одноразовые, пользовательские volumes не удаляются.

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

## Проверки STORE задачи 5

Проверено 4 октября 2026 года: `mvn -B -ntp test` — **268 тестов, без ошибок и пропусков**. STORE: 74 `store-service/src/test/java/stageTests/InventoryCartIntegrationTest.java` и 3 `StoreRuntimeTest`. Фокусированный запуск: `mvn -B -ntp -pl store-service test`. Реальные PostgreSQL 16/Kafka, HTTP random port; уникальные магазины и UUID для каждого сценария. Прямой вызов receiver применяется для отдельных транзакционных инвариантов, реальный Kafka consumer — для доставки, большого сообщения и проверки offset. Сбои SQL воспроизводятся только внутри тестового контейнера.

| CASES / инвариант | Методы InventoryCartIntegrationTest | Проверенный результат и граница |
| --- | --- | --- |
| CASES-01: STORE | `kafkaReceiptAndPublicCartApiWorkTogether` | GoodsPosted проходит настоящую Kafka, появляется одна позиция по 120.00; HTTP создаёт корзину и меняет её состав без расхода. WAREHOUSE/TARIFFS не запускаются в этом интеграционные проверки модулей. |
| CASES-03/27: приход | `duplicateBusinessPayloadWithNewEventNeverAddsStockTwice` | Прежний eventId и новый eventId идентичной поставки дают один приход. Порядок строк, scale наценки и текстовая точность одного Instant не создают новые бизнес-данные. |
| CASES-04: STORE | `changedContentCannotOverwriteReceipt`, `eventIdCannotChangeStoreOwnership`, `sequenceCannotBelongToTwoDeliveries`, `inconsistentTypeRejectsWholeNewDelivery` | Значимые изменения прежней поставки/eventId сохраняют диагностику; stock/movements неизменны. Конфликт типа или sequence отвергает всю новую поставку. |
| CASES-05/32: STORE ingress | `invalidGoodsAreDiagnosedWithoutStock`, `consumerOffsetWaitsForDiagnosticCommit` | 28 отрицательных вариантов, включая tombstone, malformed/duplicate/unknown JSON, неверный key, финансовую формулу и дубли продукта/строки. Consumer повторяет ошибку записи диагностики; offset продвигается только после durable commit. WAREHOUSE REJECTED проверяется отдельно в задаче 4. |
| CASES-08/28: открытая корзина | `replenishmentKeepsSkuAndRepricesAllRemainingUnits` | 6 по 120.00 + 10 по 144.00 → прежний stockItemId, quantity=16, цена/метаданные последней поставки. OPEN-корзина читает новую цену без изменения version. Snapshot submit ещё отсутствует. |
| CASES-09 | `delayedOlderDeliveryPreservesLatestSequencePrice` | Поздний уникальный старый приход увеличивает количество, сохраняя цену и метаданные большего sequence; повтор не добавляет количество. |
| Конкурентный приход / rollback | `concurrentReceiptsAndDuplicatesHaveOneSku`, `movementFailureRollsBackReceiptAndQuantity`, `quantityOverflowIsAtomic` | Две уникальные поставки и конкурентный дубль дают один SKU и точный баланс. SQL failure движения/переполнение не оставляют частичного receipt, processed event или stock. После устранения сбоя повтор успешно применяется. |
| CASES-14 | `independentCartsNeverReserveInventory` | Две корзины содержат по 5 при остатке 5; попытка заменить свою позицию на 6 даёт 409 без изменения version/состава. |
| CASES-15 | `concurrentCartPutsHaveOneVersionWinner`, `concurrentCartReadsNeverMixHeaderAndComposition`, `replacementAndDeletionUseExpectedVersion` | У конкурентных PUT одной версии ровно один победитель. GET при заменах показывает согласованные version/quantity; PUT заменяет количество, даже повтор прежнего значения увеличивает version. DELETE отсутствующей строки или stale version не меняет корзину. |
| CASES-20: редактирование | `closedAndExhaustedCartsCannotMutate` | SQL fixture закрытой корзины/исчерпанной version не допускает изменения. Публичный submit и чтение принятого snapshot относятся к задаче 6. |
| CASES-23: каталог/корзина | `storesAndCartsAreIsolated`, `eventIdCannotChangeStoreOwnership` | Чужой cart/stock не доступен в API магазина; одинаковые productId разных магазинов создают отдельные позиции. Submission scope ещё не реализован. |
| Quantity=0 / JSON / rollback | `zeroStockRemainsVisibleAndCannotBeAdded`, `invalidCartJsonNeverChangesState`, `cartStorageFailureRollsBackCompositionAndVersion` | Нулевой остаток виден, PUT положительного количества отвергается; удалить существующую строку можно. Невалидный JSON и storage failure откатывают состав/version. Нулевой остаток пока задаётся fixture, не покупкой. |
| Лимиты v1 | `catalogLimitIsEnforcedWithoutTruncatingKafkaMessage`, `aggregateMoneyLimitRejectsMutationAtomically` | Реальное многомегабайтное Kafka-сообщение с 1000 SKU проходит; 1001 SKU отвергается атомарно. Выход суммы за 36 цифр не сохраняет новую строку/version. |
| Legacy / runtime | `removedLegacyRoutesAndInvalidPathsCannotBypassNewModel`; StoreRuntimeTest | Старые purchase/auth/raw-product endpoints отсутствуют; ошибки безопасны. V1/V2 повторно не изменяют legacy marker и S-1/S-2. TARIFFS `legacyStoreEndpointsAreRemoved` подтверждает отсутствие старых процентных API при сохранении исторических fixtures. |

В `.github/workflows/product-flow-test.yml` Compose smoke расширен до настоящей цепочки DeliveryReceived → WAREHOUSE → TARIFFS → GoodsPosted → каталог STORE. Две HTTP-корзины независимо получают весь доступный остаток, inventory остаётся прежним. После пересоздания приложений сохранены каталог, корзина и единственное приходное движение. Результат удалённого запуска приводится в PR после выполнения. Это сквозная проверка поставки/корзин; CASES-16–19/21–22/24/29–31 и STORE-части outbox ещё ожидают задачи 6–8.

## Проверки STORE задачи 6

Проверено 4 октября 2026 года: `mvn -B -ntp -pl store-service test` — **118/118, без ошибок и пропусков**: 74 inventory/cart, 39 submission, 2 recovery и 3 runtime. Полный `mvn -B -ntp test` — **309/309** без ошибок/падений/пропусков. Новые файлы: `store-service/src/test/java/stageTests/SubmissionIntegrationTest.java`, `StoreRecoveryTest.java`. HTTP/PostgreSQL/Kafka настоящие, идентификаторы магазинов/корзин/поставок/операций независимы. Sender отключается только для управляемых module checks; отдельные recovery-тесты запускают настоящий scheduled sender.

| CASES / инвариант | Тесты | Проверенный результат и граница |
| --- | --- | --- |
| CASES-16 | SubmissionIntegrationTest: `acceptanceCommitsStockSnapshotAndOutboxTogether`, `multipleLinesUseServerOwnedExactMoney`, `senderPublishesStoredPayloadAndStopsAfterAcknowledgement` | 202 после commit; расход 3 из 10, остаток 7, сумма 360.00, один submission/outbox/expense, SUBMITTED version+1 и Location. Точные многозначные суммы, один expense на SKU. Реальный Kafka ack → PUBLISHED/publishedAt, повтор sender не создаёт расход. |
| CASES-17/21 | `depletedStockRejectsSubmitWithoutWrites`, `oneInsufficientLineRollsBackWholePurchase`, `concurrentPurchasesOfLastUnitHaveOneWinner` | Второй покупатель повторно проверяет остаток. Нехватка одной строки сохраняет весь состав OPEN и остальные остатки. В гонке за последнюю единицу ровно один 202 и один 409 INSUFFICIENT_STOCK. |
| CASES-18/19 | `acceptedRequestReplaysBeforeClosedCartAndVersionChecks`, `concurrentSameKeyAndCartReturnSameOperation`, `conflictingConcurrentKeyRecoversAfterActualUniqueRollback`, `acceptedKeyCannotChangeCanonicalRequest` | Повтор исходного key/version возвращает прежние submissionId/eventId и актуальный статус. Настоящий PostgreSQL UNIQUE при одинаковом ключе двух разных cart/SKU принудительно пересекается через тестовый barrier; проигравший rollback завершён до нового replay transaction и 409 IDEMPOTENCY_KEY_REUSED. Нет частичного расхода. |
| CASES-20/22 | `acceptanceCommitsStockSnapshotAndOutboxTogether`, `acceptedKeyCannotChangeCanonicalRequest`, `emptyStaleAndExhaustedCartCannotSubmit`, `cartEditAndSubmitCompeteForTheSameVersion` | Принятая корзина отвергает PUT/DELETE и новый submit key. Stale version, пустая корзина и исчерпанная безопасная JSON version ничего не списывают. В гонке PUT/submit одной версии проходит только одно изменение. |
| CASES-23/30 | `keysAndSubmissionLookupsAreStoreScoped` | Одинаковый ключ двух магазинов создаёт две операции. Чужая submission/cart не доступна в scope другого магазина; баланс и snapshot не смешиваются. |
| CASES-24 | `storageFailureRollsBackEveryAcceptanceWrite` | SQL triggers на expense INSERT, outbox INSERT и cart closure UPDATE падают внутри настоящей транзакции. Inventory, submission, expense, outbox, state/version полностью откатываются; после устранения сбоя тот же ключ принимается один раз. |
| CASES-25 | `kafkaOutagePreservesAcceptedExpenseAndRecovers`; StoreRecoveryTest: `scheduledSenderAutomaticallyRecoversBrokerOutage` | Pause настоящей Kafka сохраняет принятый расход и PENDING, lastError/attempt/backoff. После unpause публикация восстанавливается; scheduled вариант не вызывает ручной send/retry. Один expense, товар не возвращается и повторно не списывается. |
| CASES-26/29: backend recovery | StoreRecoveryTest: `restartRecoversCommittedAndExpiredLeasedOutbox` | Два последовательных Spring startup с прежней PostgreSQL: невыбранный и уже leased PENDING восстанавливаются автоматически. Прежний key/version возвращает исходную операцию, JSON/snapshot/acceptedAt не меняются. Browser-потеря ответа/reload отдельно проверяется набором задачи 7 ниже. |
| CASES-27 | `lostPublishedMarkReplaysSameEventWithoutNewDeduction`, `persistedLeaseFencesConcurrentAndStaleSenders` | После реального Kafka ack UPDATE PUBLISHED падает по SQL trigger. Reclaim даёт два одинаковых физических event JSON с прежним eventId и один расход. Конкурентный claim имеет одного владельца; старый token не меняет новую lease или publication state. |
| CASES-28 | `submittedSnapshotSurvivesFuturePriceAndNameChanges`, `replenishmentAndSubmitPreserveBalanceAndConsistentPrice` | Новая цена до submit входит в snapshot; следующие цена/имя не меняют принятую корзину/OrderSubmitted. Конкурентные приход и submit заканчиваются точным балансом и цельной старой либо новой ценой, без потерянного quantity update. |
| Повтор отвергнутого запроса / валидация | `rejectedKeyCanBeRetriedAfterReplenishment`, `invalidSubmitJsonCannotPurchase`, `invalidKeysCannotPurchase` | Не принятый ключ не занимает uniqueness; после пополнения исходные cart/version/key проходят. Пропущенные/лишние/дублированные поля, неверный тип/version и недопустимые/пропущенные keys не создают purchase writes. |
| Лимиты v1 | `submitRejectsMoneyOverflowAfterRepricing`, `thousandLineOrderTraversesKafkaWithoutTruncation` | SQL price fixture изолирует overflow суммы после переоценки: 400 без списания/закрытия. Валидный 1000-line Unicode cart fixture создаёт >1 MiB OrderSubmitted, который проходит настоящую Kafka без усечения; 1000 расходов и точный баланс. Ingress проверяется отдельными сценариями задач 4–5. |

В consumer-offset проверке задачи 5 устранена гонка тестового ожидания: durable diagnostic и committed offset становятся наблюдаемыми в разное время. Тест ждёт наличие OffsetAndMetadata перед проверкой значения, без произвольного sleep.

Compose smoke задачи 6 добавляет submit на 3 единицы, ожидание PUBLISHED и повтор принятого ключа. После пересоздания приложений проверяются остаток 7, SUBMITTED snapshot/version, publication status и единственный expense/outbox. Результат remote source-build запуска указывается в PR. Browser-покрытие CASES-29/31 добавлено задачей 7 ниже; полный Kotlin E2E остаётся задачей 8. Исполнение/оплата не входят в проект.

## Покрытие HTML задачи 7

`e2e-tests/src/test/kotlin/org/golenev/tests/e2e_tests/HtmlE2ETest.kt` использует Kotlin + Selenide и настоящие STORE/WAREHOUSE/TARIFFS/Kafka/PostgreSQL/Redis. У каждого сценария свои productId/deliveryId и browser contexts с независимым sessionStorage. Тесты идут последовательно в собственном Compose project; исходные кеш/fixtures восстанавливаются, созданная дельта очищается. Маршрутизация тестового браузера управляет только сетевыми окнами; backend продолжает выполнять настоящие транзакции.

| CASES / инвариант | Функция теста | Проверяемый результат |
| --- | --- | --- |
| CASES-01/14/15/16/17/21 | `independentCarts` | Поставка через HTML до STORE, две корзины с полным остатком без резерва, первая покупка проходит, вторая показывает INSUFFICIENT_STOCK и сохраняет состав. |
| CASES-28 | `priceSnapshot` | Пополнение меняет цену открытой корзины; новая поставка после оформления не меняет принятые 480.00. |
| CASES-18/29/31 | `lostReply` | Реальный 202 после commit теряется, закрытая cart/version не меняет сохранённый запрос. Reload повторяет прежний key/body; остаток уменьшается один раз, виден PUBLISHED. |
| CASES-18/29/31 | `ambiguous503` | Прокси возвращает 503 после настоящего commit. Повтор использует прежний ключ и прежний расход. |
| CASES-18/29/31 | `lostRequest` | Прокси отклоняет первый POST до STORE завершённым пустым 503 с Content-Length: 0. Наблюдатели тела и ключа зарегистрированы после фильтра отказа и выполняются перед ним. До reload остаток прежний и расходов нет; повтор исходного key/body принимает заявку один раз. |
| CASES-22 | `versionConflict` | Сторонний PUT меняет version/quantity; stale submit показывает конфликт и перечитывает состав. Следующая попытка покупает актуальные 2 единицы. |
| Безопасный вывод / деньги v1 | `safeRendering`, `exactLargeMoney` | Имя с img/onerror и описание со script отображаются буквально; DOM не содержит внедрённых тегов. Отдельный `exactLargeMoney` проверяет точный серверный total 23418718062326581.80 выше точности Number. |
| CASES-02: сетевой повтор поставщика | `supplierRetry` | После Kafka ack ответ теряется; reload повторяет весь envelope, поставка/приход не удваиваются. Это последовательный browser retry; backend-конкуренция покрыта предыдущими задачами. |
| CASES-23/30/31 | `storesAndPages`, `preservedPages`, `mobileLayout` | S-2 открывает свою пустую корзину, возврат S-1 восстанавливает прежний состав; отдельный `mobileLayout` проверяет ширину 390 без overflow, `preservedPages` — новые API без global cart/order/auth и Authorization. |

`ui-tests/common.test.js`: `exactMoney` и `invalidMoney` проверяют нормализацию десятичных строк и отказ для нуля, exponent, отрицательного/неполного значения и лишней точности. `UiSettingsTest` проверяет публичный GET конфигурации и отклонение unsafe URL. `WarehouseUiCorsTest` проверяет реальные MVC preflight: разрешённые exact origins и JSON POST/GET, запрет постороннего origin/retry-pricing, отсутствие credentials и отказ для некорректной конфигурации.

Локальная проверка задачи 7: Maven reactor **324/324**, unit JS **2/2**. Итоговый браузерный прогон после пересборки HTML — **9/9**, без failures/skipped. CI собирает приложения из исходников и запускает эти же HTML-сценарии после Compose smoke/restart; скриншоты сохраняются artifacts. Актуальная матрица Kotlin/module/browser находится ниже.

## Историческая матрица задачи 8 (удалённые классы не являются текущим покрытием)

Запуск backend E2E: штатный Docker Compose, затем Gradle из `e2e-tests`; команды и переменные приведены в README. Ожидания используют Kotest eventually и зафиксированное состояние сервиса. SQL DAO возвращают списки сохранённых строк; тесты сами считают количество и суммы и проверяют единственность результата.

| CASES | Класс и метод | Уровень и проверяемая граница |
| --- | --- | --- |
| 01 | ProductFlowE2ETest: `realSupplyPreservesReceivingMetadata` | Kotlin: HTTP supplier → Kafka → pricing → GoodsPosted → STORE; IDs, metadata, sequence, quote и денежные строки. |
| 02 | DeliveryIntegrationTest | Интеграционная проверка конкурентного повтора; соответствующий многопоточный E2E удалён. |
| 03 | ProductFlowE2ETest: `goodsDuplicateWithNewEventIdNeverAddsCredit` | Kotlin: транспортный и бизнес-повтор без увеличения остатка. |
| 04 | ProductFlowE2ETest: `changedSupplierContentCannotOverwriteReceipt`, `changedGoodsCannotOverwriteInventory` | Kotlin: конфликт сохранён в диагностике, исходный результат не изменён. |
| 05 | ProductFlowE2ETest: `malformedDeliveryHasDurableDiagnostic`, `unknownVersionHasNoReceipt`, `invalidQuantityIsRejected`; DeliveryIntegrationTest: `kafkaOffsetWaitsForDurableDiagnostic` | Kotlin: настоящий ingress и durable diagnostic/REJECTED; module: failure записи блокирует commit offset, после восстановления оба состояния наблюдаемы отдельно. |
| 06 | DeliveryIntegrationTest, WarehouseRecoveryTest | Интеграционные проверки восстановления расчёта; сквозной сценарий отключения TARIFFS удалён. |
| 07 | WarehouseRecoveryTest: `restartAutomaticallyRecoversPricingLeaseAndPendingOutbox` | Интеграционная проверка восстановления; E2E не перезапускает контейнеры. |
| 08 | DeliveryIntegrationTest, WarehouseRecoveryTest | Интеграционные проверки окон сохранения и восстановления; управляемые задержки E2E удалены. |
| 09 | InventoryCartIntegrationTest: `delayedOlderDeliveryPreservesLatestSequencePrice` | Интеграционная проверка порядка цен; E2E с задержкой расчёта удалён. |
| 10 | TariffApiE2ETest: `cacheSnapshotRequiresResetAndNewSupply` | Kotlin: CRUD не меняет заполненный quote, reset меняет следующие quotes; inventory не переоценивается до поставки. |
| 11 | TariffApiE2ETest: `cacheSnapshotRequiresResetAndNewSupply`; TariffApiTest: `scheduledResetUsesMoscowMidnightAndTheSameNamespace`, `resetDoesNotFlushOtherRedisNamespaces` | Kotlin: manual reset; module: cron/timezone и сохранность чужих namespaces, без ожидания реальных суток. |
| 12 | TariffApiE2ETest: `tariffBoundarySelectsOneRule`, `absentRuleIsAnError`, `ambiguousRulesAreAnError`, `fractionalPricingRoundsHalfUp` | Kotlin: шесть граничных цен, 404/409, HALF_UP до 0.51. Остальные типы/города и overflow — TariffApiTest/DeliveryIntegrationTest. |
| 13 | TariffApiTest: `redisOutageFallsBackToDatabaseAndResetReturns503` | Интеграционная проверка деградации кеша; E2E не отключает Redis. |
| 14 | OrderE2ETest: `independentCartsNeverReserve` | Kotlin: две полные корзины без резерва, превышение своего количества отклоняется. |
| 15 | InventoryCartIntegrationTest | Интеграционная проверка конкурентного изменения корзины; соответствующий E2E удалён. |
| 16 | OrderE2ETest: `acceptancePersistsExactSnapshotAndEvent` | Kotlin: 202, quantity 7, snapshot 360.00, закрытие cart и точное сохранённое событие outbox; физическое сообщение Kafka дополнительно проверяют `UiOrderKafkaE2ETest.shouldPublishCompleteOrderAfterUiDeliveryAndCheckout` и `shouldPublishEveryLineOfUiOrderWithDifferentTariffs`, а также интеграционные тесты модулей. |
| 17 | OrderE2ETest: `insufficientOneLineNeverPartiallyDeducts` | Kotlin: чужая покупка одной строки, отсутствие частичного расхода остальных. |
| 18 | OrderE2ETest: `acceptedRequestReplaysOriginalVersion` | Kotlin: последовательный повтор исходных ключа и версии сохраняет одну операцию. Конкурентный E2E удалён. |
| 19 | OrderE2ETest: `acceptedKeyCannotChangeRequest` | Kotlin: другой cart/version с принятым ключом получает конфликт без нового расхода. |
| 20 | OrderE2ETest: `closedCartRejectsNewExpenseAndMutation` | Kotlin: закрытая cart отвергает mutation и новый submit key. |
| 21 | SubmissionIntegrationTest | Интеграционная проверка конкурентной покупки последней единицы; соответствующий E2E удалён. |
| 22 | OrderE2ETest: `staleVersionCannotPurchase` | Kotlin: stale version не изменяет inventory/outbox/cart. |
| 23 | OrderE2ETest: `storeScopesSeparateKeysAndResources` | Kotlin: чужие cart/stock/submission не доступны в другом scope. |
| 24 | SubmissionIntegrationTest: `storageFailureRollsBackEveryAcceptanceWrite` | Интеграционная проверка полного отката; сквозной SQL-сбой и его DDL удалены. |
| 25 | StoreRecoveryTest, DeliveryIntegrationTest | Интеграционные проверки восстановления отправки после отказа Kafka; E2E не приостанавливает брокер. |
| 26 | StoreRecoveryTest, WarehouseRecoveryTest | Интеграционные проверки восстановления сохранённой работы; E2E не перезапускает контейнеры. |
| 27 | SubmissionIntegrationTest, DeliveryIntegrationTest | Интеграционные проверки повторной отправки; управляемые задержки после подтверждения Kafka удалены. |
| 28 | ProductFlowE2ETest: `replenishmentRepricesStockAndFreezesAcceptedSnapshot` | Kotlin: новая цена до submit, неизменяемый snapshot после следующего пополнения. |
| 29 | HtmlE2ETest: `lostResponse`, `ambiguous503`, `lostRequest` | Browser: повреждённый JSON и 503 через встроенный прокси Selenide до/после принятия, сохранённые key/body и один расход. TCP-обрыв не проверяется. |
| 30 | OrderE2ETest: `storeScopesSeparateKeysAndResources` | Kotlin: одинаковая строка ключа двух магазинов создаёт две независимые операции. |
| 31 | HtmlE2ETest: `storesAndPages`; OrderE2ETest: `independentCartsNeverReserve` | Переключение магазина сохраняет корзины; последовательное наполнение двух корзин не резервирует товар. |
| 32 | ProductFlowE2ETest: `duplicateProductLinesAreRejected` | Kotlin: два одинаковых productId → REJECTED, без прихода. |
| 33 | TariffApiE2ETest: `newRuleAutomaticallyUnblocksWaitingDelivery` | Kotlin: отсутствующее правило добавлено, автоматический pricing и один приход без retry API. |
| 34 | DeliveryIntegrationTest: `manualRetryAndReclaimedWorkerCannotPostTwice` | Интеграционная проверка конкурирующих попыток; E2E с задержкой расчёта удалён. |

Отдельный профиль e2e, таблицы управляемых задержек, команды остановки Docker и соответствующие 12 сквозных сценариев удалены по ревью. Восстановление сервисов по-прежнему проверяют интеграционные тесты модулей; матрица выше явно отмечает этот уровень.

CI: Maven reactor → Kotlin E2E на штатном Compose. Отчёты сохраняются как JUnit XML/HTML, Allure raw results и логи Compose. До добавления UI→Kafka-сценариев предыдущий набор локально прошёл: 39/39; результат удалённого CI проверяется отдельно.

Историческая проверка предыдущей версии: Подтверждённая локальная проверка задачи 8: **326 Maven + 44 Kotlin E2E**, без ошибок и пропусков. Runtime-profile assertions отдельно подтверждены обычным стартом обоих сервисов. Полный E2E выполнен с Maven-built JAR; source-build/HTML проверяются отдельными CI jobs.

## Исправления E2E по ревью

Текущий набор: 28 серверных запусков и 13 самостоятельных HTML-проверок, включая 2 новых UI→Kafka-сценария. Каждый тест создаёт свои поставки, товары и корзины. Данные после теста сохраняются; независимость обеспечивают уникальные идентификаторы и отсутствие резерва в корзинах. ScenarioTemplate и ScenarioResources удалены.

REST работает через Rest Assured Response и сервисные DAO; чтение и подготовка БД — через конкретный Exposed DSL. ObservationDao и самодельные проверяющие обёртки удалены. SQL DAO только читают и записывают данные, включая выборки через join. Количество строк, суммы движений и единственность события outbox проверяются в тестах; пустая выборка остаётся пустым списком. Ожидания используют awaitPoll: конфигурация, runBlocking и eventually находятся внутри утилиты.

UI использует один драйвер, DriverConfig, страницы, встроенный прокси и читаемый listener по образцу golenev-xlsx-report-system. Бизнес-шаги Allure показывают предусловия, изменения состояния и итоговые проверки; транспортные операции и страницы имеют вложенные @Step. Перехват ключа и тела запроса сохраняет проверку браузерных повторов без новых списаний.

Проверка 4 октября 2026 после merge PR #46: Maven clean verify — 324/324, JavaScript unit — 2/2. После исправлений E2E полный текущий набор прошёл 39/39 (28 серверных и 11 браузерных), без ошибок и пропусков. Приложения собраны штатным Dockerfile из исходников в отдельном Compose-проекте; тесты выполнялись на настоящих PostgreSQL/Kafka/Redis и трёх приложениях. Backend и JavaScript не менялись и после исправлений повторно не запускались.

`ProductFlowE2ETest.unknownVersionHasNoReceipt` ждёт сохранённую диагностику неизвестной версии, затем явно проверяет HTTP 404 и пустой каталог. `HtmlE2ETest.lostRequest` проверяет отказ до STORE, отсутствие расхода, повтор прежних тела и ключа после reload и единственное списание. Пустой 503 завершается через Content-Length: 0; наблюдатели регистрируются после фильтра отказа, поскольку BrowserUp выполняет фильтры запросов в обратном порядке. Перехватчики тела ограничивают ожидание после действия и снимаются в finally.

Конкурентные E2E и race удалены по ревью. HTTP-транспорт соответствует референсу без ветвлений и технических шагов; ожидаемый статус передаётся из негативных сценариев в DAO. Одноразовые фильтры отказа и подмены статуса удаляются после теста. Бизнес-шаги разделены по состояниям поставки и корзины.

Проверка DAO (5 октября 2026): CASES-01/03/04/05/10/16/17/18/19/22/23/28/29 и повтор поставщика сохранили проверки прихода, расхода, диагностик и outbox. DAO возвращают списки строк; количество и суммы вычисляются в сценариях, включая нулевую сумму пустой выборки. Чтение расходов продукта сохраняет join с inventory, а отсутствие outbox читается через LEFT JOIN без проверок внутри DAO. Полный `.\e2e-tests\gradlew.bat -p e2e-tests --offline test` на JDK 21 — **39/39** (28 серверных и 11 браузерных), без ошибок и пропусков. Использовано сохранённое Compose-окружение `shop-postmerge-a1fb085`; backend и JavaScript не менялись и повторно не проверялись.

## Чёрный ящик: поставка через HTML до сообщения заявки

Файл: `e2e-tests/src/test/kotlin/org/golenev/tests/e2e_tests/UiOrderKafkaE2ETest.kt`. Бизнес-действия выполняются только браузером, окончательная заявка читается из Kafka. SQL и outbox DAO не используются. Прокси наблюдает настоящий ответ submit без подмены; productId уникальны, consumer начинает чтение до поставок и выбирает событие по submissionId.

| CASES | Метод / Allure ID | Проверяемая граница |
| --- | --- | --- |
| 01, 12, 16 | `shouldPublishCompleteOrderAfterUiDeliveryAndCheckout` / 161 | HTML-поставка 10 × 100.05 → тарифная цена 120.06 → HTML-заявка на 3 → реальный OrderSubmitted на 360.18. Все поля события/позиции, метаданные, UTC, UUID, Kafka key; остаток 7 через каталог. |
| 01, 12, 16 | `shouldPublishEveryLineOfUiOrderWithDifferentTariffs` / 162 | Две HTML-поставки с тарифами 20%/25% → одна HTML-заявка 2 × 120.00 + 1 × 625.00. Все поля обеих позиций без зависимости от порядка, точная сумма 865.00, остатки 8 и 3. |

Команда: `.\e2e-tests\gradlew.bat -p e2e-tests test --tests org.golenev.tests.e2e_tests.UiOrderKafkaE2ETest`. Проверено 6 октября 2026 года на JDK 21 и работающем штатном `shop-runtime`: `.\e2e-tests\gradlew.bat -p e2e-tests --offline test --tests org.golenev.tests.e2e_tests.UiOrderKafkaE2ETest --tests org.golenev.tests.e2e_tests.HtmlE2ETest` — **13/13** (2 новых и 11 существующих браузерных E2E), без ошибок и пропусков. Проверены Allure JSON, бизнес-шаги и вложения настоящих сообщений Kafka. Backend, JavaScript и 28 серверных E2E в этой задаче не изменялись и повторно не запускались; результат удалённого CI проверяется отдельно. Чтение одной записи не является проверкой отсутствия допустимых физических повторов события.

## Учебные CRUD-сценарии тарифов

Девять сценариев `TAR-CRUD-001`–`TAR-CRUD-009` и сопоставление уровней описаны в [реестре пирамиды](docs/testing-pyramid.md). Новый API-класс — `org.golenev.tests.backend.blackbox.TariffCrudApiTest`: создание, чтение, полная замена, удаление и отказ для отсутствующих UUID либо неверных границ. Данные и результаты прежние; структура перенесена из `golenev-xlsx-report-system/e2e-test`, ответы разбираются в data class и проверяются в бизнес-шагах. 8 октября 2026 отдельный запуск на JDK 21 и работающем `shop-runtime` — **9/9**, без ошибок и пропусков. Старые сценарии защищённого пакета и Kafka не изменены и повторно не запускались.

В новом API-наборе названия тестов и шаги Allure описывают жизненный цикл одного тарифного правила: условия наценки и город при создании, изменение либо отказ, чтение и проверку итогового состояния. Технические запросы показаны вложенными шагами DAO. После уточнения нейминга 8 октября — **9/9**; проверены JSON Allure, стабильные ID, последовательность бизнес-шагов и вложения запросов/ответов. Защищённые E2E не изменены.

Подготовка данных нового API-набора вынесена в отдельные шаги Allure: выбор изолированного города, исходные условия наценки, допустимая замена и намеренно равные границы 100.00 рублей. Название шага объясняет конкретные условия и причину недопустимости; подготовка отделена от отправки и изменения сохранённого правила.

По отдельному запросу на очистку тестового дерева удалены 13 неиспользуемых функций `Shop` и неиспользуемое свойство `Scope.city`, оставшиеся после удаления серверных тестов. Используемые `publish`, `catalog`, `stock`, `put` и `Scope.store` сохранены без изменения реализации; классы защищённого E2E-пакета не изменены. Пустые каталоги прежних пакетов `pyramid` удалены из локального checkout; Git пустые каталоги не хранит.

9 октября 2026 HTTP-набор тарифов перенесён в `backend.blackbox`; рядом добавлен `backend.whitebox.TariffCrudDatabaseApiTest`: девять сопоставимых CRUD-сценариев плюс три обратные проверки прямого изменения/удаления строки и чтения списка. Данные подготавливаются и фиксируются через Exposed, ответы проверяются через API; POST/PUT/DELETE дополнительно проверяются независимым чтением PostgreSQL. Реестр ID и команды — в `docs/testing-pyramid.md`. Production, защищённые E2E и их существующая обвязка не изменены.

Проверка дополнения 9 октября: JDK 21, совместный blackbox/whitebox API-прогон — **21/21** (9 + 12), без ошибок и пропусков. Проверены шаги и вложения Allure; строки 12 собственных городов whitebox-набора удалены. Старые E2E и Java-набор повторно не запускались.

9 октября 2026 сервисные Java-тесты разнесены по пакетам `pyramid.logic/module/http/database`. Во всех трёх сервисах добавлен `pyramid.api_database`: реальные MVC-контроллеры и PostgreSQL Testcontainers с production Flyway, SQL-подготовка и независимая проверка изменений. STORE — 14 новых сценариев, TARIFFS — 12, WAREHOUSE — 9. Kafka и защищённые E2E не изменены. Полный реестр ID, границы и команды — в `docs/testing-pyramid.md`.

Проверка сервисного дополнения 9 октября: полный `mvn clean verify` на JDK 21 — **133/133**, без ошибок и пропусков. В том числе 35 новых API↔PostgreSQL проверок (STORE 14, TARIFFS 12, WAREHOUSE 9), 62 неизменённых контракта и 36 прежних сервисных проверок. Для шести перенесённых классов подтверждено, что изменилась только строка package. Production и Kotlin E2E не изменены; Kotlin-прогон повторно не запускался. Фактическая команда использовала `-o -B -ntp -s target/service-api-maven-settings.xml` и заполненный кеш; локальный пустой settings не входит в Git.
