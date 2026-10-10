package com.tariffs.dto;

import com.tariffs.model.Rule;

import java.util.List;

/**
 * Список текущих тарифных правил, прочитанный из PostgreSQL и упорядоченный по UUID.
 *
 * @param items текущие тарифные правила в порядке UUID
 */
public record RulesResponse(List<Rule> items) { }
