package dev.semeniuk.cashmatch.invoicing;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("customers")
public record Customer(@Id UUID id, String name, String country) {}
