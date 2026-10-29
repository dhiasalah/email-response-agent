package com.emailagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The owner's password is stored as a salted, slow hash; anything malformed or weak never matches. */
class PasswordHashTest {

    @Test
    void aHashMatchesOnlyItsPassword() {
        String stored = PasswordHash.hash("correct horse battery staple");
        assertThat(stored).startsWith("pbkdf2-sha256:600000:").doesNotContain("correct horse");
        assertThat(PasswordHash.matches("correct horse battery staple", stored)).isTrue();
        assertThat(PasswordHash.matches("correct horse battery stapl", stored)).isFalse();
        assertThat(PasswordHash.hash("same")).isNotEqualTo(PasswordHash.hash("same")); // salted
    }

    @Test
    void malformedOrWeakHashesFailClosed() {
        assertThat(PasswordHash.matches("x", null)).isFalse();
        assertThat(PasswordHash.matches("x", "")).isFalse();
        assertThat(PasswordHash.matches("x", "md5:abc")).isFalse();
        assertThat(PasswordHash.matches("x", "pbkdf2-sha256:1000:AAAA:AAAA")).isFalse(); // too few iterations
        assertThat(PasswordHash.matches("x", "pbkdf2-sha256:600000:%%%:AAAA")).isFalse();
    }
}
