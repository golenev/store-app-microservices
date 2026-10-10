package com.tariffs.dto;


/** Имя поля и безопасное описание нарушения без отклонённого значения. */
public record ErrorDetail(String field, String message) { }
