package com.emailagent.web;

import com.emailagent.config.AppConfig;
import io.quarkus.logging.Log;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Checks the owner's login (design doc §10: one owner, one panel). Username and password hash come from the
 * environment; no hash configured means no login at all (fail closed). After {@link #MAX_FAILURES} failures in
 * {@link #WINDOW}, every attempt is refused until the window passes, even with the right password: guessing is slow.
 */
@ApplicationScoped
public class OwnerIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    public static final String ROLE = "owner";
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final String username;
    private final String passwordHash;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Clock clock = Clock.systemUTC();

    public OwnerIdentityProvider(AppConfig config) {
        this.username = config.ownerUsername();
        this.passwordHash = config.ownerPasswordHash().orElse(null);
        if (passwordHash == null) {
            Log.warn("emailagent.owner-password-hash is not set: the review panel refuses every login");
        }
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {
        // PBKDF2 is deliberately slow: off the event loop
        return context.runBlocking(() -> {
            if (!check(request.getUsername(), new String(request.getPassword().getPassword()))) {
                throw new AuthenticationFailedException();
            }
            return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(username)).addRole(ROLE)
                    .build();
        });
    }

    /** True for the owner's credentials, unless too many failures were seen recently. */
    synchronized boolean check(String user, String password) {
        Instant now = clock.instant();
        while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(WINDOW))) {
            failures.pollFirst();
        }
        if (failures.size() >= MAX_FAILURES) {
            Log.warn("owner login refused: too many recent failures");
            return false;
        }
        // compare the password even for a wrong username, so the timing doesn't reveal which one was wrong
        boolean ok = PasswordHash.matches(password, passwordHash) & username.equals(user);
        if (!ok) {
            failures.addLast(now);
        }
        return ok;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    synchronized void resetFailures() {
        failures.clear();
    }
}
