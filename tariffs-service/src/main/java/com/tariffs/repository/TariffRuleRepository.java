package com.tariffs.repository;

import com.tariffs.dto.RuleRequest;
import com.tariffs.model.Rule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Читает и сохраняет тарифные правила в PostgreSQL. Записи и блокировки входят в транзакции {@code
 * TariffRuleService}.
 */
@Repository
public class TariffRuleRepository {
    private final JdbcTemplate jdbc;

    /**
     * Подключает выполнение SQL к текущей транзакции Spring.
     *
     * @param jdbc выполнение SQL с участием в текущей транзакции Spring
     */
    public TariffRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Находит правила по типу товара, городу, валюте и закупочной цене. Нижнюю границу включает, верхнюю
     * исключает. Возвращает не более двух правил: этого достаточно, чтобы сервис различил отсутствие,
     * единственное совпадение и неоднозначный выбор.
     *
     * @param productType тип товара: {@code FOOD} или {@code NON_FOOD}
     * @param cityId идентификатор города, для которого выбирается тариф
     * @param currency код валюты; в текущем контракте разрешён {@code RUB}
     * @param price закупочная цена как точное десятичное число
     * @return найденные тарифные правила; при отсутствии совпадений список пуст
     */
    public List<Rule> matching(String productType, String cityId, String currency, BigDecimal price) {
        return jdbc.query("""
                SELECT * FROM tariff_rules WHERE product_type=? AND city_id=? AND currency=?
                AND lower_bound <= ? AND (upper_bound IS NULL OR upper_bound > ?)
                ORDER BY tariff_rule_id LIMIT 2
                """, this::map, productType, cityId, currency, price, price);
    }

    /**
     * Читает правило по UUID без блокировки. Если записи нет, возвращает пустой {@code Optional}.
     *
     * @param id UUID тарифного правила
     * @return найденное правило или пустой результат, если записи нет
     */
    public Optional<Rule> find(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=?", this::map, id).stream().findFirst();
    }

    /**
     * Читает и блокирует правило до конца транзакции сервиса. Это позволяет последовательно увеличивать
     * версию; отсутствие записи возвращает как пустой {@code Optional}.
     *
     * @param id UUID тарифного правила
     * @return найденное правило или пустой результат, если записи нет
     */
    public Optional<Rule> lock(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=? FOR UPDATE", this::map, id).stream().findFirst();
    }

    /**
     * Возвращает правила в порядке UUID, не более 1001 записи. Дополнительная запись нужна сервису для
     * обнаружения превышения лимита в 1000 правил.
     *
     * @return найденные тарифные правила; при отсутствии совпадений список пуст
     */
    public List<Rule> list() {
        return jdbc.query("SELECT * FROM tariff_rules ORDER BY tariff_rule_id LIMIT 1001", this::map);
    }

    /**
     * Получает общую блокировку создания правил до конца транзакции. Другой запрос создания ждёт её
     * освобождения, чтобы проверка количества и новая запись не превысили лимит каталога.
     */
    public void lockCatalog() {
        jdbc.execute("SELECT pg_advisory_xact_lock(731003)");
    }

    /**
     * Возвращает число правил в текущей транзакции. При создании сервис вызывает метод после получения общей
     * блокировки каталога.
     *
     * @return число сохранённых тарифных правил
     */
    public long count() { return jdbc.queryForObject("SELECT count(*) FROM tariff_rules", Long.class); }

    /**
     * Сохраняет правило с указанным UUID и версией 1. Условия должны быть заранее проверены сервисом; ошибка
     * БД отменяет транзакцию создания.
     *
     * @param id UUID тарифного правила
     * @param request полный набор условий тарифного правила
     */
    public void insert(UUID id, RuleRequest request) {
        jdbc.update("""
                INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,upper_bound,markup_rate)
                VALUES(?,1,?,?,?,?,?,?)
                """, id, request.productType(), request.cityId(), request.currency(),
                new BigDecimal(request.lowerBound()), decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()));
    }

    /**
     * Полностью заменяет поля правила и увеличивает версию на один. Сервис должен заранее заблокировать
     * правило; сохранённые расчёты в Redis метод не меняет.
     *
     * @param id UUID тарифного правила
     * @param request полный набор условий тарифного правила
     */
    public void replace(UUID id, RuleRequest request) {
        jdbc.update("""
                UPDATE tariff_rules SET version=version+1,product_type=?,city_id=?,currency=?,
                lower_bound=?,upper_bound=?,markup_rate=? WHERE tariff_rule_id=?
                """, request.productType(), request.cityId(), request.currency(), new BigDecimal(request.lowerBound()),
                decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()), id);
    }

    /**
     * Удаляет правило по UUID. Возвращает {@code true}, если строка удалена, и {@code false}, если её не было.
     *
     * @param id UUID тарифного правила
     * @return {@code true}, если правило удалено; {@code false}, если его не было
     */
    public boolean delete(UUID id) { return jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?", id) == 1; }

    /**
     * Переводит строковую границу цены в точное десятичное число. Для {@code null} возвращает {@code null},
     * сохраняя отсутствие верхней границы.
     *
     * @param value строковая граница цены или {@code null} для отсутствующей верхней границы
     * @return точное десятичное значение границы или {@code null}
     */
    private BigDecimal decimalOrNull(String value) { return value == null ? null : new BigDecimal(value); }

    /**
     * Преобразует строку SQL-результата в правило с UUID, версией и строковыми ценами. Ошибку чтения колонки
     * передаёт как {@code SQLException}.
     *
     * @param row текущая строка результата SQL-запроса
     * @param rowNumber номер строки SQL-результата; на преобразование не влияет
     * @return правило с UUID, версией и полным набором условий
     */
    private Rule map(ResultSet row, int rowNumber) throws SQLException {
        BigDecimal upper = row.getBigDecimal("upper_bound");
        return new Rule(row.getObject("tariff_rule_id", UUID.class), row.getLong("version"),
                row.getString("product_type"), row.getString("city_id"), row.getString("currency"),
                row.getBigDecimal("lower_bound").toPlainString(), upper == null ? null : upper.toPlainString(),
                formatRate(row.getBigDecimal("markup_rate")));
    }

    /**
     * Записывает наценку строкой без изменения значения. Удаляет лишние нули после точки, но оставляет не
     * менее двух десятичных знаков.
     *
     * @param value наценка как точное десятичное число
     * @return наценка строкой без лишних нулей, минимум с двумя знаками после точки
     */
    private String formatRate(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }
}
