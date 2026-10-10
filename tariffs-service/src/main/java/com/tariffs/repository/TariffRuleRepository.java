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

/** Работает с версионными правилами через JDBC; транзакциями управляет TariffRuleService. */
@Repository
public class TariffRuleRepository {
    private final JdbcTemplate jdbc;

    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param jdbc JDBC-адаптер с участием в текущей транзакции Spring
     */
    public TariffRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Возвращает до двух правил по типу продукта, городу, валюте и price; сервис различает отсутствие,
     * единственное правило и неоднозначность.
     *
     * @param productType тип продукта FOOD или NON_FOOD
     * @param cityId идентификатор города выбора тарифа
     * @param currency валюта денежного значения
     * @param price точная денежная цена без floating point
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Rule> matching(String productType, String cityId, String currency, BigDecimal price) {
        return jdbc.query("""
                SELECT * FROM tariff_rules WHERE product_type=? AND city_id=? AND currency=?
                AND lower_bound <= ? AND (upper_bound IS NULL OR upper_bound > ?)
                ORDER BY tariff_rule_id LIMIT 2
                """, this::map, productType, cityId, currency, price, price);
    }

    /**
     * Читает правило id без блокировки записи; отсутствие возвращает Optional.empty.
     *
     * @param id UUID запрашиваемого объекта
     * @return найденное значение или пустой результат при отсутствии
     */
    public Optional<Rule> find(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=?", this::map, id).stream().findFirst();
    }

    /**
     * Блокирует правило id до конца транзакции сервиса для последовательного увеличения версии; отсутствие
     * возвращает Optional.empty.
     *
     * @param id UUID запрашиваемого объекта
     * @return найденное значение или пустой результат при отсутствии
     */
    public Optional<Rule> lock(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=? FOR UPDATE", this::map, id).stream().findFirst();
    }

    /**
     * Возвращает упорядоченные правила и дополнительную строку для выявления превышения лимита без молчаливого
     * усечения.
     *
     * @return строки выборки; пустой список означает отсутствие совпадений
     */
    public List<Rule> list() {
        return jdbc.query("SELECT * FROM tariff_rules ORDER BY tariff_rule_id LIMIT 1001", this::map);
    }

    /**
     * Сериализует создание правил advisory-блокировкой до конца транзакции сервиса для соблюдения лимита
     * каталога.
     */
    public void lockCatalog() {
        jdbc.execute("SELECT pg_advisory_xact_lock(731003)");
    }

    /**
     * Возвращает число сохранённых правил в текущей транзакции после блокировки создания каталога.
     */
    public long count() { return jdbc.queryForObject("SELECT count(*) FROM tariff_rules", Long.class); }

    /**
     * Записывает id с версией 1 и проверенными сервисом данными request; ошибка отменяет транзакцию создания.
     *
     * @param id UUID запрашиваемого объекта
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    public void insert(UUID id, RuleRequest request) {
        jdbc.update("""
                INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,upper_bound,markup_rate)
                VALUES(?,1,?,?,?,?,?,?)
                """, id, request.productType(), request.cityId(), request.currency(),
                new BigDecimal(request.lowerBound()), decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()));
    }

    /**
     * Заменяет заблокированное правило id данными request и увеличивает версию один раз; Redis не изменяет.
     *
     * @param id UUID запрашиваемого объекта
     * @param request HTTP-запрос или параметры контракта согласно типу
     */
    public void replace(UUID id, RuleRequest request) {
        jdbc.update("""
                UPDATE tariff_rules SET version=version+1,product_type=?,city_id=?,currency=?,
                lower_bound=?,upper_bound=?,markup_rate=? WHERE tariff_rule_id=?
                """, request.productType(), request.cityId(), request.currency(), new BigDecimal(request.lowerBound()),
                decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()), id);
    }

    /**
     * Удаляет правило id и возвращает наличие удалённой строки в текущей транзакции.
     *
     * @param id UUID запрашиваемого объекта
     * @return признак успешной операции согласно проверке выше
     */
    public boolean delete(UUID id) { return jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?", id) == 1; }

    /**
     * Преобразует денежную строку value в BigDecimal, сохраняя null как отсутствие верхнего предела.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private BigDecimal decimalOrNull(String value) { return value == null ? null : new BigDecimal(value); }

    /**
     * Преобразует строку row в версионное правило с точными строковыми денежными значениями; ошибка чтения
     * распространяется как SQLException.
     *
     * @param row строка результата JDBC
     * @param rowNumber номер строки JDBC, не влияющий на отображение
     */
    private Rule map(ResultSet row, int rowNumber) throws SQLException {
        BigDecimal upper = row.getBigDecimal("upper_bound");
        return new Rule(row.getObject("tariff_rule_id", UUID.class), row.getLong("version"),
                row.getString("product_type"), row.getString("city_id"), row.getString("currency"),
                row.getBigDecimal("lower_bound").toPlainString(), upper == null ? null : upper.toPlainString(),
                formatRate(row.getBigDecimal("markup_rate")));
    }

    /**
     * Возвращает строку value с дробной наценкой без изменения численного значения.
     *
     * @param value исходное значение, формат и ограничения которого описаны выше
     */
    private String formatRate(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }
}
