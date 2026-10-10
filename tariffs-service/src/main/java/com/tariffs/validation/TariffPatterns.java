package com.tariffs.validation;

/** Форматы строк публичного контракта тарифов. */
public final class TariffPatterns {
    public static final String MONEY = "^(0|[1-9][0-9]{0,25})\\.[0-9]{2}$";
    public static final String RATE = "^(0|[1-9][0-9]{0,2})\\.[0-9]{1,6}$";
    public static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$";
    /**
     * Исключает создание экземпляров набора форматов.
     */
    private TariffPatterns() { }
}
