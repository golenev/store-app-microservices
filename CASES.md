# Ключевые сквозные сценарии

Статус: спецификация целевого поведения. Новые бизнес-сценарии/E2E ещё не реализованы и не выполнены. В задаче 1 реализована проверка структурных контрактов, в задаче 2 — runtime и миграции (см. ниже). Эти проверки и семь legacy unit-тестов перенесённого STORE не являются выполнением новых E2E. Каждый PR обновляет статус соответствующих сценариев и добавляет ссылку на реальный тест.

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

Для всех записей ниже первоначальный статус: **запланирован**. Номера задач соответствуют implementation-plan.md.

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
