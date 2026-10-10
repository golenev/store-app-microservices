package com.tariffs.validation;

/**
 * Хранит разрешённые форматы строковых полей HTTP API тарифов для проверки входных данных.
 */
public final class TariffPatterns {
    public static final String MONEY = "^(0|[1-9][0-9]{0,25})\\.[0-9]{2}$";
    public static final String RATE = "^(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}$";
    public static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$";
    /**
     * Запрещает создание объекта: форматы используются через статические поля класса.
     */
    private TariffPatterns() { }
}
