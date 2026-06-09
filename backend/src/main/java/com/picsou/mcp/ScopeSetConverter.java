package com.picsou.mcp;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps an access-key's scope {@code Set<String>} to a single space-delimited column and back.
 * Stored sorted for deterministic, diff-friendly persistence; read in full on the hot auth path
 * (one column, no join table). Applied explicitly via {@code @Convert} — never {@code autoApply}
 * (which would hijack every {@code Set<String>} field in the model).
 */
@Converter
public class ScopeSetConverter implements AttributeConverter<Set<String>, String> {

    @Override
    public String convertToDatabaseColumn(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return "";
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    @Override
    public Set<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(dbData.trim().split("\\s+"))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
