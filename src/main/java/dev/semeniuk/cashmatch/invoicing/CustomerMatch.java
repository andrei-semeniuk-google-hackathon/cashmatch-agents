package dev.semeniuk.cashmatch.invoicing;

/** A customer found by fuzzy name search; similarity is pg_trgm's score in [0, 1]. */
public record CustomerMatch(Customer customer, double similarity) {}
