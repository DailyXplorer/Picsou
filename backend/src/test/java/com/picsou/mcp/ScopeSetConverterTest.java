package com.picsou.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeSetConverterTest {

    private final ScopeSetConverter converter = new ScopeSetConverter();

    @Test
    void toColumn_joinsSortedSpaceDelimited() {
        // Insertion order is goals:write, accounts:read — storage must be deterministic (sorted).
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("goals:write");
        scopes.add("accounts:read");
        assertThat(converter.convertToDatabaseColumn(scopes))
            .isEqualTo("accounts:read goals:write");
    }

    @Test
    void toColumn_emptySet_isEmptyString() {
        assertThat(converter.convertToDatabaseColumn(Set.of())).isEmpty();
    }

    @Test
    void toColumn_null_isEmptyString() {
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
    }

    @Test
    void toAttribute_splitsOnWhitespace() {
        assertThat(converter.convertToEntityAttribute("accounts:read goals:write"))
            .containsExactlyInAnyOrder("accounts:read", "goals:write");
    }

    @Test
    void toAttribute_null_isEmptySet() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }

    @Test
    void toAttribute_blank_isEmptySet() {
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void toAttribute_toleratesExtraWhitespace() {
        assertThat(converter.convertToEntityAttribute("  accounts:read   goals:write  "))
            .containsExactlyInAnyOrder("accounts:read", "goals:write");
    }

    @Test
    void roundTrip_preservesScopes() {
        Set<String> original = Set.of("accounts:read", "transactions:write", "sync:trigger");
        String column = converter.convertToDatabaseColumn(original);
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(original);
    }
}
