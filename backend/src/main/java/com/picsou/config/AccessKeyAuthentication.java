package com.picsou.config;

import com.picsou.model.AppUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * The {@link org.springframework.security.core.Authentication} an access-key request runs as.
 *
 * <p>The principal is the owning {@link AppUser} — so {@code UserContext.currentMemberId()} resolves
 * to that member's data unchanged — but the concrete type is deliberately <em>distinct</em> from the
 * {@code UsernamePasswordAuthenticationToken} the cookie filters set. That type difference is the seam
 * that lets {@code UserContext} refuse the admin {@code ?memberId=} impersonation override for keys
 * (no impersonation — security Property B). Authorities are scope strings only, never a {@code ROLE_*}
 * (Property C), so role-gated paths such as {@code /api/admin/**} stay structurally unreachable.
 */
public class AccessKeyAuthentication extends AbstractAuthenticationToken {

    private final transient AppUser principal;
    private final Long keyId;

    public AccessKeyAuthentication(AppUser principal, Collection<? extends GrantedAuthority> authorities, Long keyId) {
        super(authorities);
        this.principal = principal;
        this.keyId = keyId;
        setAuthenticated(true);
    }

    /** The raw secret is validated then discarded — it is never retained on the Authentication. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public Long getKeyId() {
        return keyId;
    }
}
