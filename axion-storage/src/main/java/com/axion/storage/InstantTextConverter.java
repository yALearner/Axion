package com.oryxos.storage;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;

/** {@link Instant} ↔ ISO-8601 TEXT 转换（SQLite 无原生 TIMESTAMP）。 */
@Converter
public class InstantTextConverter implements AttributeConverter<Instant, String> {

  @Override
  public String convertToDatabaseColumn(Instant attribute) {
    return attribute == null ? null : attribute.toString();
  }

  @Override
  public Instant convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Instant.parse(dbData);
  }
}
