package com.picsou.exception;

/**
 * Raised by {@code ScopeEnforcementAspect} when the current principal calls an MCP tool whose
 * {@code @RequiresScope} is not among its granted authorities. Mapped to 403 in
 * {@link GlobalExceptionHandler} so the AI client gets a clear "missing scope" signal.
 */
public class MissingScopeException extends RuntimeException {
    public MissingScopeException(String scope) {
        super("Missing required scope: " + scope);
    }
}
