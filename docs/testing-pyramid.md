# Учебный набор уровней тестирования

Новый набор позволяет сравнить одинаковые CRUD-сценарии тарифных правил на четырёх границах проверки: Mockito, учебный HTTP-клиент с WireMock, PostgreSQL и публичный API. Чистые функции проверяются отдельно. Реестр ниже связывает бизнес-сценарий с ID каждой реализации; это ID нового набора, а не номера исторических CASES.

Существующие тесты STORE, TARIFFS, WAREHOUSE и три серверных Kotlin-класса заменены по запросу пользователя. Контрактный модуль, пакет `org.golenev.tests.e2e_tests` и его обвязка сохранены. Production-код, SQL-миграции, HTML и правила бизнеса не изменены. Старые проверки восстановления, конкуренции и Kafka вне защищённого пакета удалены; новый учебный набор не объявляется равнозначной заменой их покрытия.

## Как различаются уровни

| Уровень | Что работает своим кодом | Что подменено | Какие ошибки можно обнаружить |
| --- | --- | --- | --- |
| Чистая логика, `logic` | Форматирование ставки, проверка цены и идентификатора | Внешних вызовов нет | Неверный формат, изменение точности, неправильная граница |
| Модуль, `module` | TariffRuleService и Bean Validation | TariffRuleRepository через Mockito | Неправильная реакция сервиса, параметры сохранения, лишние вызовы |
| HTTP, `http` | Учебный TariffRulesHttpClient, RestClient, Jackson и HTTP-сервер WireMock | Сервер задаёт ответы вместо TARIFFS | Неверный метод/путь/тело запроса, чтение ответа, преобразование статуса и кода ошибки |
| PostgreSQL, `database` | TariffRuleService, транзакционный прокси Spring, репозиторий, миграции и движок PostgreSQL | Внешние сервисы не участвуют | SQL, сохранение полей и версий, отсутствие записи после отказа |
| Развёрнутая система, `api` | Приложение TARIFFS и его инфраструктура | Ничего | Ошибки маршрутов, HTTP-статусов, сериализации, конфигурации и сохранения через API |

Контейнер не является отдельным уровнем. Testcontainers создаёт отдельную PostgreSQL со своей схемой и данными. Это проверка интеграции компонентов с БД, даже если приложение целиком не запускается. Успешный ответ Mockito не доказывает, что SQL выполнится; PostgreSQL-проверка это проверяет. Верхний API-набор знает только публичные маршруты: после записи выполняет GET вместо SQL.

`ReflectionUtils` находит и вызывает существующие функции. Он не создаёт Spring beans, не подменяет зависимости и не активирует `@Transactional`. Выбраны функции, работающие только с входными значениями. У formatRate и price открывается private-доступ; identifier публичный и мог бы вызываться напрямую. Имена методов проверяются при запуске, поэтому переименование private-функции требует обновления теста.

Одинаковое число сценариев на нескольких уровнях выбрано для обучения. В обычной пирамиде быстрых изолированных проверок больше, чем дорогих проверок развёрнутой системы. Простой CRUD API-тест проверяет развёрнутую систему, но сам по себе не проходит весь пользовательский путь поставки и оформления заявки. Этот путь остаётся в защищённом E2E-пакете.

## Общие данные и девять сценариев CRUD

Исходное правило: NON_FOOD, RUB, lowerBound=0.00, upperBound=500.00, markupRate=0.20. Полная замена: FOOD, RUB, lowerBound=5.00, upperBound=null, markupRate=0.30. Ошибочный ввод: границы 100.00 и 100.00. null верхнего предела означает отсутствие верхней границы, а не отсутствие поля запроса.

UUID создаётся независимо для каждого сценария; неизвестный UUID обозначает отсутствующую запись. В изолированных тестах город PYRAMID, во внешнем API — уникальный PYR-UUID. Это изоляция инфраструктуры: значение cityId не меняет проверяемые правила. Подготовка записи на Mockito-уровне задаёт ответ зависимости; на других уровнях выполняется реальное создание. Действия и ожидаемые бизнес-поля совпадают. Дополнительные проверки вызовов Mockito и HTTP Location относятся к соответствующей границе.

| ID сценария | Исходное состояние, действие и результат | Метод на уровнях Mockito, WireMock и PostgreSQL |
| --- | --- | --- |
| TAR-CRUD-001 | Создать исходное правило. UUID задан сервером, version=1, все поля сохранены | createsRule |
| TAR-CRUD-002 | Правило версии 1 существует. Прочитать по UUID: все поля совпадают | readsRule |
| TAR-CRUD-003 | Заменить правило целиком: UUID прежний, version=2, все новые поля и null сохранены | replacesRule |
| TAR-CRUD-004 | Удалить существующее правило. Чтение возвращает NOT_FOUND/404 | deletesRule |
| TAR-CRUD-005 | Прочитать отсутствующий UUID: NOT_FOUND/404 | rejectsMissingRead |
| TAR-CRUD-006 | Заменить отсутствующий UUID: NOT_FOUND/404, запись не создаётся | rejectsMissingUpdate |
| TAR-CRUD-007 | Удалить отсутствующий UUID: NOT_FOUND/404 | rejectsMissingDelete |
| TAR-CRUD-008 | Создать правило с равными границами: VALIDATION_ERROR/400, новых правил нет | rejectsInvalidCreate |
| TAR-CRUD-009 | Заменить существующее правило вводом с равными границами: VALIDATION_ERROR/400, исходные поля и version сохранены | rejectsInvalidUpdate |

Для каждой строки существуют ID с суффиксами `-MOCK`, `-HTTP`, `-DB`, `-API`. Например, TAR-CRUD-003-MOCK, TAR-CRUD-003-HTTP, TAR-CRUD-003-DB и TAR-CRUD-003-API — четыре реализации одного сценария полной замены. ID присутствуют в документации и DisplayName Java-тестов. API-сценарии сохраняют ID в AllureId; их DisplayName описывает поведение тарифного правила без технического префикса.

| Суффикс ID | Исходный файл | Подготовка и проверка состояния |
| --- | --- | --- |
| MOCK | [TariffCrudMockitoTest](../tariffs-service/src/test/java/com/tariffs/pyramid/TariffCrudMockitoTest.java) | Явные ответы Mockito; реальная валидация; проверка вызовов репозитория |
| HTTP | [TariffCrudWireMockTest](../tariffs-service/src/test/java/com/tariffs/pyramid/TariffCrudWireMockTest.java) | Реальные запросы учебного клиента; ответы и состояние списка заданы WireMock; точная проверка метода, пути и тела |
| DB | [TariffCrudPostgresTest](../tariffs-service/src/test/java/com/tariffs/pyramid/TariffCrudPostgresTest.java) | Production Flyway и сервис; отдельный контейнер; чтение после завершения транзакции |
| API | [TariffCrudApiTest](../e2e-tests/src/test/kotlin/org/golenev/tests/backend/blackbox/TariffCrudApiTest.kt) | POST/GET/PUT/DELETE публичного API; собственный город; адресное удаление собственных правил |

В PostgreSQL-наборе Spring создаёт только datasource, JdbcTemplate, репозиторий, Validator и транзакционный сервис. Тест не оборачивается общей откатываемой транзакцией: последующий get проверяет результат уже завершённого вызова. Девять сценариев подтверждают перечисленные результаты; они не доказывают конкурентную безопасность или восстановление после рестарта.

API-набор использует структуру golenev-xlsx-report-system/e2e-test: BaseSpecification, RequestExecutor<T>, ResponseValidator и TariffCrudServiceDao в org.golenev.restapi.crud. ResponseValidator перенесён непосредственно из референса; RequestExecutor дополнен PUT по образцу остальных методов. DAO принимает RuleInput и возвращает Response Rest Assured; тест разбирает TariffRule и ApiError через .as(...), список — через отдельный TariffRulesResponse. Проверки Kotest и шаги Allure явно находятся в сценариях. Общая подготовка — createRuleTemplate. Map, JsonNode, PyramidResponse и строковый диспетчер HTTP-методов удалены. Существующие DTO, старые DAO и защищённая обвязка не изменены. Подготовка и очистка данных выполняются только через HTTP, без SQL и сброса общего кеша.

## Граница WireMock

В production WAREHOUSE есть TariffClient для GET /tariffs/quote, но нет HTTP-клиента CRUD. Для сохранения одинаковых девяти CRUD-сценариев добавлен отдельный учебный [TariffRulesHttpClient](../tariffs-service/src/test/java/com/tariffs/pyramid/http/TariffRulesHttpClient.java) только в src/test. Он выполняет запросы своим кодом; WireMock возвращает явно заданные ответы. Production-код ради примера не меняется.

Этот HTTP-набор проверяет интеграцию учебного клиента с HTTP, а не TariffRuleService. Равенство списка после отказа и version=2 при замене в этом наборе заданы ответами сервера. Они показывают правильное чтение результата, но не доказывают сохранение или откат. За эти свойства отвечают DB/API-наборы. Запросы проверяются по методу, пути и полному JSON, включая upperBound=null. Наличие зависимости spring-cloud-contract-wiremock не означает выполнение контрактных тестов или изменение защищённого contract-tests.

WireMock и PostgreSQL — направления интеграционного тестирования: первое проверяет HTTP-границу, второе SQL-границу. PostgreSQL не является обязательным «следующим уровнем» после WireMock. В обоих случаях приложение целиком поднимать необязательно.

## Независимые проверки чистой логики

| ID | Вход и ожидаемый результат | Файл и метод |
| --- | --- | --- |
| STORE-LOGIC-001 | Цена 100.05 сохраняется без изменения | [StorePureLogicTest](../store-service/src/test/java/com/shop/store/pyramid/StorePureLogicTest.java), preservesExactPrice |
| STORE-LOGIC-002 | Цена 0.00 отклоняется как VALIDATION_ERROR | StorePureLogicTest, rejectsZeroPrice |
| STORE-LOGIC-003 | Цена 100.005 отклоняется, а не округляется | StorePureLogicTest, rejectsExcessPrecision |
| WH-LOGIC-001 | Идентификатор D-1 сохраняется | [WarehousePureLogicTest](../warehouse-service/src/test/java/com/shop/warehouse/pyramid/WarehousePureLogicTest.java), acceptsIdentifier |
| WH-LOGIC-002 | D 1 отклоняется из-за пробела | WarehousePureLogicTest, rejectsWhitespace |
| WH-LOGIC-003 | 64 символа принимаются, 65 отклоняются | WarehousePureLogicTest, checksLengthBoundary |
| TAR-LOGIC-001 | Ставка 0.200000 превращается в 0.20 | [TariffPureLogicTest](../tariffs-service/src/test/java/com/tariffs/pyramid/TariffPureLogicTest.java), removesTrailingZeros |
| TAR-LOGIC-002 | Ставка 0.123456 сохраняет все цифры | TariffPureLogicTest, preservesFractionalPrecision |
| TAR-LOGIC-003 | Нулевая ставка форматируется как 0.00 | TariffPureLogicTest, formatsZero |

Эти девять методов не связаны с CRUD-ID. Они показывают расчёты и валидацию отдельных значений; выполнение ручек, SQL и взаимодействие компонентов не проверяются.

## Запуск

JDK 21 обязателен. Из корня репозитория:

```shell
# Контрактный модуль, сохранённый без изменений
mvn -pl contract-tests test
# Чистая логика и Mockito без Docker
mvn -pl store-service,tariffs-service,warehouse-service "-Dgroups=logic,module" test
# Девять HTTP-сценариев с WireMock без Docker
mvn -pl tariffs-service -Dgroups=http test
# Девять PostgreSQL-сценариев; Docker обязателен
mvn -pl tariffs-service -Dgroups=database test
# Контракты и весь сервисный набор
mvn test
```

Развёрнутая система и отдельный API-набор, PowerShell:

```powershell
docker compose up -d --build --wait
.\e2e-tests\gradlew.bat -p e2e-tests test --tests org.golenev.tests.backend.blackbox.TariffCrudApiTest
```

На Linux: `bash e2e-tests/gradlew -p e2e-tests test --tests org.golenev.tests.backend.blackbox.TariffCrudApiTest`.
`PYRAMID_TARIFFS_URL` меняет адрес нового API-набора, по умолчанию http://localhost:6790. У защищённого E2E остаются прежние переменные E2E_*.

Контракты не добавляются в сопоставление CRUD. Проверки Kafka на нижних уровнях не создаются. Полный запуск Gradle включает 9 blackbox, 12 whitebox и сохранённые 13 тестов защищённого пакета; запуск только API-класса не является результатом этих 13 E2E.

## Результаты проверки

7 октября 2026 на JDK 21:

- Чистый Maven clean verify: **98/98**, без ошибок и пропусков — 62 неизменённых контрактных, 9 чистой логики, 9 Mockito, 9 WireMock, 9 PostgreSQL.
- Отдельный `TariffCrudApiTest`: **9/9**, без ошибок и пропусков. Приложения запущены в отдельном Compose-проекте shop-pyramid на портах 17689/17690/17691; PostgreSQL 15477, Kafka 19097, Redis 16397. Production JAR собраны Maven из текущей ветки и подключены к локальным runtime-образам. Шесть сервисов прошли штатные healthcheck.
- Весь Kotlin-набор, включая сохранённые классы и обвязку, компилируется. Сохранённые 13 E2E в этой задаче не запускались; их успех не выводится из CRUD-прогона.
- Git diff для contract-tests, production-исходников, защищённого E2E-пакета, прежних src/main/src/test/utils, Gradle-настроек и Compose пустой.

Команды фактического локального прогона: `mvn -o -B -ntp -s target/pyramid-maven-settings.xml clean verify` и `gradlew.bat -p e2e-tests --offline test --tests org.golenev.tests.backend.blackbox.TariffCrudApiTest` с PYRAMID_TARIFFS_URL=http://localhost:17690. Локальный Maven settings пустой и использует заполненный кеш зависимостей; он не входит в Git. Обычные воспроизводимые команды приведены выше. Удалённый CI проверяется отдельно.

### Проверка после переработки API-набора, 8 октября 2026

`ResponseValidator` нового CRUD-набора совпадает с исходником референса после исключения имени пакета и добавленного KDoc. `BaseSpecification` и `RequestExecutor` перенесены по тому же образцу; адрес адаптирован к TARIFFS и добавлен PUT для полной замены. Существующая обвязка защищённых E2E сохранена; новые транспортные классы расположены в `org.golenev.restapi.crud`. `PyramidResponse`, нетипизированные тела и вспомогательные обёртки проверки удалены. Все девять ID и бизнес-сценарии сохранены.

Команда `.\e2e-tests\gradlew.bat -p e2e-tests --offline test --tests org.golenev.tests.backend.blackbox.TariffCrudApiTest` на JDK 21 — **9/9**, без ошибок и пропусков. Использовано работающее приложение `shop-runtime`, TARIFFS http://localhost:6790. Тесты удалили только собственные правила. Весь Kotlin-модуль скомпилирован; контрактные, Java-тесты и защищённые E2E повторно не запускались.


### Маршруты тарифного правила в Allure

В новом API-наборе субъект — тарифное правило для товаров конкретного города. Названия сценариев и бизнес-шаги описывают его исходные условия, изменение либо отказ и наблюдаемый итог. Идентификатор сохранённого правила связывает шаги одной истории. Технические HTTP-операции показаны вложенными шагами DAO; тела запросов и ответов сохранены во вложениях. ID и девять бизнес-сценариев не изменены. Имена API-методов приведены отдельно после перехода на стиль `should...`:

| ID | Метод API-теста |
| --- | --- |
| TAR-CRUD-001-API | shouldCreateTariffRuleWithVersionOne |
| TAR-CRUD-002-API | shouldReadTariffRuleWithOriginalTerms |
| TAR-CRUD-003-API | shouldReplaceTariffRuleTermsWithVersionTwo |
| TAR-CRUD-004-API | shouldDeleteCreatedTariffRule |
| TAR-CRUD-005-API | shouldRejectReadingMissingTariffRule |
| TAR-CRUD-006-API | shouldRejectReplacingMissingTariffRuleWithoutCreatingIt |
| TAR-CRUD-007-API | shouldRejectDeletingMissingTariffRule |
| TAR-CRUD-008-API | shouldRejectCreatingTariffRuleWithEmptyPriceRange |
| TAR-CRUD-009-API | shouldPreserveTariffRuleAfterRejectedReplacement |

После изменения нейминга: отдельный API-прогон **9/9**. Проверены фактические JSON в `e2e-tests/build/allure-results`: девять успешных результатов с уникальными прежними ID, бизнес-маршруты одного субъекта, вложенные HTTP-шаги DAO и существующие файлы вложений запросов/ответов.

Подготовка данных нового API-набора вынесена в отдельные шаги Allure: выбор изолированного города, исходные условия наценки, допустимая замена и намеренно равные границы 100.00 рублей. Название шага объясняет конкретные условия и причину недопустимости; подготовка отделена от отправки и изменения сохранённого правила.

После добавления подготовки данных отдельный API-прогон — **9/9**. В фактических Allure JSON проверены шаги подготовки каждого сценария, выбор города в предусловии и порядок: недопустимые условия подготовлены до попытки создания или замены.

По отдельному запросу на очистку тестового дерева удалены 13 неиспользуемых функций `Shop` и неиспользуемое свойство `Scope.city`, оставшиеся после удаления серверных тестов. Используемые `publish`, `catalog`, `stock`, `put` и `Scope.store` сохранены без изменения реализации; классы защищённого E2E-пакета не изменены. Пустые каталоги прежних пакетов `pyramid` удалены из локального checkout; Git пустые каталоги не хранит.

Проверка очистки: `gradlew.bat -p e2e-tests --offline testClasses` на JDK 21 — успешно; весь Kotlin-модуль компилируется. Тела четырёх сохранённых методов `Shop` сравнены с предыдущим коммитом и совпадают. Повторный запуск сценариев не выполнялся: удалены только недостижимые функции и пустые каталоги.

## Blackbox и whitebox API тарифных правил

Существующие девять HTTP-сценариев перенесены без изменения поведения в `org.golenev.tests.backend.blackbox.TariffCrudApiTest`. Их ID `TAR-CRUD-001-API`–`TAR-CRUD-009-API` сохранены; чтения БД в этом классе нет.

Новый класс [TariffCrudDatabaseApiTest](../e2e-tests/src/test/kotlin/org/golenev/tests/backend/whitebox/TariffCrudDatabaseApiTest.kt) расположен рядом в `backend.whitebox`. Он обращается к развёрнутой TARIFFS через тот же сервисный DAO и непосредственно к её PostgreSQL через Exposed: `DbFactory`, `dbTariffsExec`, отдельные `TariffRulesTable`, `TariffRuleRow`, `TariffRulesDao`. Каждый DAO-вызов выполняется в отдельной транзакции; запись фиксируется до запроса, проверка читает результат после завершения HTTP-операции. Бизнес-проверки находятся в тестах.

Оба набора проверяют работающее приложение. Доступ к БД меняет способ подготовки и независимой проверки, а не добавляет новую ступень пирамиды. Первые девять whitebox-сценариев повторяют прежние CRUD-сценарии; ещё три добавлены по запросу для обратного пути и списка. Область этого дополнения — `/tariffs/rules` и `/tariffs/rules/{id}`: GET, POST, PUT, DELETE. Котировки, сброс кеша и Kafka сюда не входят.

| ID | Подготовка, действие, независимый результат | Метод |
| --- | --- | --- |
| TAR-CRUD-001-WHITE | Подготовить условия → POST → SQL: одна строка, все поля, версия 1 и соответствие ответу | shouldCreateTariffRuleInDatabase |
| TAR-CRUD-002-WHITE | Подготовить строку → INSERT → GET по идентификатору: все условия совпадают | shouldReadTariffRulePreparedInDatabase |
| TAR-CRUD-003-WHITE | INSERT исходной строки → подготовить новые условия → PUT → SQL: новые поля, тот же идентификатор, версия 2 | shouldReplaceTariffRuleInDatabase |
| TAR-CRUD-004-WHITE | INSERT → DELETE через HTTP → SQL: строки нет → GET: NOT_FOUND | shouldDeleteTariffRuleFromDatabase |
| TAR-CRUD-005-WHITE | SQL: идентификатора нет → GET: NOT_FOUND → SQL: строки по-прежнему нет | shouldRejectReadingMissingTariffRule |
| TAR-CRUD-006-WHITE | SQL: идентификатора нет → подготовить допустимую замену → PUT: NOT_FOUND → SQL и GET: правило не создано | shouldRejectReplacingMissingTariffRule |
| TAR-CRUD-007-WHITE | SQL: идентификатора нет → DELETE: NOT_FOUND → SQL: строки нет | shouldRejectDeletingMissingTariffRule |
| TAR-CRUD-008-WHITE | SQL: город пуст → подготовить равные границы 100.00 → POST: VALIDATION_ERROR → SQL: город остался пустым | shouldRejectCreatingInvalidTariffRule |
| TAR-CRUD-009-WHITE | INSERT → подготовить равные границы 100.00 → PUT: VALIDATION_ERROR → SQL: полный снимок не изменился → GET: исходные условия | shouldPreserveTariffRuleAfterRejectedReplacement |
| TAR-CRUD-010-WHITE | INSERT → подготовить новые условия и версию 2 → SQL UPDATE → GET: новые условия и null верхнего предела | shouldReadTariffRuleAfterDirectDatabaseReplacement |
| TAR-CRUD-011-WHITE | INSERT → SQL DELETE → GET: NOT_FOUND | shouldRejectReadingTariffRuleDeletedDirectly |
| TAR-CRUD-012-WHITE | INSERT → GET списка: правило своего города и все его условия | shouldListTariffRulePreparedInDatabase |

Подготовка, сохранение, воздействие и проверка состояния показаны отдельными русскими шагами Allure. Данные совпадают с остальными CRUD-наборами: непродовольственные товары, RUB, 0.00–500.00, наценка 20%; замена — продовольственные товары, от 5.00 без верхнего предела, наценка 30%. У каждого теста отдельный город `WHT-UUID`; очистка через SQL удаляет только строки этого города, включая записи после промежуточного падения. Таблицы и volumes не пересоздаются. Чтение списка ограничено контрактом приложения: при превышении 1000 правил API возвращает ошибку; тесты не обходят это ограничение.

Запуск на JDK 21 из корня:

```powershell
# Поднять приложение со штатными зависимостями, если оно ещё не работает
docker compose up -d --build --wait
# Только независимые API↔БД проверки
.\e2e-tests\gradlew.bat -p e2e-tests test --tests org.golenev.tests.backend.whitebox.TariffCrudDatabaseApiTest
# Оба набора
.\e2e-tests\gradlew.bat -p e2e-tests test --tests org.golenev.tests.backend.blackbox.TariffCrudApiTest --tests org.golenev.tests.backend.whitebox.TariffCrudDatabaseApiTest
```

HTTP-адрес задаётся `PYRAMID_TARIFFS_URL` либо `E2E_TARIFFS_URL`; порт PostgreSQL — `E2E_POSTGRES_PORT` (по умолчанию 34567), база `tariffs_db`. Эти адреса должны указывать на один экземпляр TARIFFS и его базу. Подключение использует штатную роль `tariffs_app` существующего DbFactory.

### Проверка дополнения, 9 октября 2026

Совместный `gradlew.bat -p e2e-tests --offline test` с двумя указанными фильтрами — **21/21**, без ошибок и пропусков: 9 blackbox и 12 whitebox. JDK 21, существующие контейнеры `shop-runtime-postgres-1`, `shop-runtime-redis-1`, `shop-runtime-tariffs-service-1`; PostgreSQL 34567, TARIFFS 6790. После отказа соединений в первом запуске остановленные контейнеры запущены без пересоздания данных. TARIFFS и зависимости прошли healthcheck.

Проверены фактические Allure JSON: 21 успешный результат, уникальные стабильные ID, подготовка и маршруты, вложенные HTTP/SQL-операции и файлы вложений. Независимая SQL-проверка подтвердила отсутствие строк всех 12 городов whitebox-прогона после очистки. Blackbox-файл отличается от прежнего только строкой package. Контрактные, Java- и защищённые E2E-тесты не изменены и повторно не запускались; весь Kotlin-модуль компилируется.
