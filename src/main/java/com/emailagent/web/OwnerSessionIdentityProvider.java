package com.emailagent.web;

import com.emailagent.config.AppConfig;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Restores the owner from the session cookie after login. The cookie is encrypted and signed by Quarkus, so its
 * username can't be forged; it must still be the owner's, and no hash configured means no session either.
 */
@ApplicationScoped
public class OwnerSessionIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    private final String username;
    private final boolean enabled;

    public OwnerSessionIdentityProvider(AppConfig config) {
        this.username = config.ownerUsername();
        this.enabled = config.ownerPasswordHash().isPresent();
    }

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
            AuthenticationRequestContext context) {
        if (!enabled || !username.equals(request.getPrincipal())) {
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(username)).addRole(OwnerIdentityProvider.ROLE).build());
    }
}
