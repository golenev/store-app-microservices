package helpers

import java.math.BigDecimal
import java.math.RoundingMode


/**
 * Если число целое, то приводим к масштабу 0, убирая знаки после точки
 * Если дробное число,то округляем до 2 знаков после запятой
 * Для унификации с нашим бекендом
 */
fun BigDecimal.halfUpRound(scaleValue: Int = 2): BigDecimal {
   return if (this.stripTrailingZeros().scale() <= 0) {
        // Целое число: приводим к масштабу 2, добавляя .00
        this.setScale(0)
    } else {
        // Дробное число: округляем до 2 знаков после запятой
        this.setScale(scaleValue, RoundingMode.HALF_UP)
    }
}
