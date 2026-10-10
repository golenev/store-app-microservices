package com.tariffs.dto;

import com.tariffs.model.Rule;

import java.util.List;

/** Упорядоченный список актуальных правил из PostgreSQL. */
public record RulesResponse(List<Rule> items) { }
