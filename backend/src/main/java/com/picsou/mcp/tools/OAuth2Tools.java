package com.picsou.mcp.tools;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.config.OAuthClientProperties;
import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.service.MfaService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * MCP tools over the OAuth2 authorization server ({@code /oauth2/**}), which today serves a single
 * first-party public client (the native iOS app, Authorization Code + PKCE, no client secret — see
 * {@code AuthorizationServerConfig}). Both tools here are read-only reflections of state that
 * already exists: the static server metadata, and the calling access-key's own status. Neither
 * touches the authorization-server filter chain, its {@code RegisteredClientRepository}, or issues
 * any token — see {@code STATUS.md} for why {@code request_oauth2_token} was not built.
 */
@Component
public class OAuth2Tools {

    private final AuthorizationServerSettings authorizationServerSettings;
    private final OAuthClientProperties oAuthClientProperties;
    private final AccessKeyService accessKeyService;
    private final MfaService mfaService;
    private final UserContext userContext;

    public OAuth2Tools(AuthorizationServerSettings authorizationServerSettings,
                       OAuthClientProperties oAuthClientProperties,
                       AccessKeyService accessKeyService,
                       MfaService mfaService,
                       UserContext userContext) {
        this.authorizationServerSettings = authorizationServerSettings;
        this.oAuthClientProperties = oAuthClientProperties;
        this.accessKeyService = accessKeyService;
        this.mfaService = mfaService;
        this.userContext = userContext;
    }

    /** Static discovery response: issuer/endpoint paths + this server's single client. No secrets. */
    public record OAuth2Configuration(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwkSetEndpoint,
        String clientId,
        boolean pkceRequired,
        List<String> supportedScopes
    ) {}

    @Tool(name = "get_oauth2_configuration",
        description = "Get the Picsou OAuth2 authorization server's discovery metadata: issuer, "
            + "authorize/token/JWKS endpoint paths, the registered client id, and whether PKCE is "
            + "required. Read-only, no secrets. Every key can call this regardless of its other scopes.")
    @RequiresScope(Scopes.OAUTH2_DISCOVER)
    public OAuth2Configuration getOAuth2Configuration() {
        return new OAuth2Configuration(
            authorizationServerSettings.getIssuer(),
            authorizationServerSettings.getAuthorizationEndpoint(),
            authorizationServerSettings.getTokenEndpoint(),
            authorizationServerSettings.getJwkSetEndpoint(),
            oAuthClientProperties.getClientId(),
            true,
            List.of("read", "write")
        );
    }

    /** The calling access-key's own status, plus its owner's MFA posture. Never another key's. */
    public record OAuth2SessionStatus(
        Long keyId,
        String keyName,
        Set<String> scopes,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean mfaEnabled
    ) {}

    @Tool(name = "get_oauth2_session_status",
        description = "Get the status of the access-key session this MCP call is authenticated with: "
            + "its name, granted scopes, creation/last-used/expiry timestamps, and whether the owning "
            + "member has MFA enabled. A key can only ever see its own status.")
    @RequiresScope(Scopes.OAUTH2_SESSION_STATUS)
    public OAuth2SessionStatus getOAuth2SessionStatus() {
        Long keyId = currentKeyId();
        AccessKey key = accessKeyService.list(userContext.currentMemberId()).stream()
            .filter(k -> k.getId().equals(keyId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Access key not found for the current session"));
        AppUser owner = userContext.currentUser();
        return new OAuth2SessionStatus(
            key.getId(),
            key.getName(),
            key.getScopes(),
            key.getCreatedAt(),
            key.getLastUsedAt(),
            key.getExpiresAt(),
            mfaService.isEnabled(owner)
        );
    }

    /**
     * Only an {@link AccessKeyAuthentication} ever calls MCP tools (Property A), so its
     * {@code keyId} identifies exactly the session this tool call is running under.
     */
    private Long currentKeyId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AccessKeyAuthentication keyAuth) {
            return keyAuth.getKeyId();
        }
        throw new IllegalStateException("MCP tools must run under an access-key session");
    }
}
