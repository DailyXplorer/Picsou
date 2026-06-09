package com.picsou.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopesTest {

    @Test
    void all_containsExactlyTheDocumentedScopes() {
        assertThat(Scopes.ALL).containsExactlyInAnyOrder(
            "accounts:read", "transactions:read", "goals:read",
            "dashboard:read", "prices:read", "family:read",
            "accounts:write", "transactions:write", "goals:write",
            "sync:trigger"
        );
    }

    @Test
    void all_isImmutable() {
        assertThatThrownBy(() -> Scopes.ALL.add("evil:scope"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constants_exposeTheirLiteralValues() {
        // Constants are referenced from @RequiresScope, so they must equal the wire strings.
        assertThat(Scopes.ACCOUNTS_READ).isEqualTo("accounts:read");
        assertThat(Scopes.TRANSACTIONS_WRITE).isEqualTo("transactions:write");
        assertThat(Scopes.SYNC_TRIGGER).isEqualTo("sync:trigger");
    }
}
