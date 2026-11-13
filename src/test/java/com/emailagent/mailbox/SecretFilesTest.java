package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Gmail refresh token opens the owner's whole mailbox and the client file holds the client secret: on disk, only
 * the owner's account may read them (design doc §13, access control). POSIX permissions on Linux, an ACL on Windows.
 */
class SecretFilesTest {

    static void assertOwnerOnly(Path p) throws Exception {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(p)).as(p.toString())
                    .isEqualTo(PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
            return;
        }
        // the current user, not the file's owner: an admin's new files can belong to BUILTIN\Administrators
        UserPrincipal me = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> acl = Files.getFileAttributeView(p, AclFileAttributeView.class).getAcl();
        assertThat(acl).as(p.toString()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(e.principal()).isEqualTo(me);
        });
    }

    @Test
    void writtenFileIsReadableOnlyByItsOwner(@TempDir Path dir) throws Exception {
        Path token = dir.resolve("tokens").resolve("gmail.json");

        SecretFiles.writeOwnerOnly(token, "{\"refresh_token\":\"r\"}");

        assertThat(Files.readString(token)).isEqualTo("{\"refresh_token\":\"r\"}");
        assertOwnerOnly(token);
        assertOwnerOnly(token.getParent());
    }

    @Test
    void overwriteKeepsOwnerOnly(@TempDir Path dir) throws Exception {
        Path token = dir.resolve("gmail.json");

        SecretFiles.writeOwnerOnly(token, "a");
        SecretFiles.writeOwnerOnly(token, "b");

        assertThat(Files.readString(token)).isEqualTo("b");
        assertOwnerOnly(token);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).containsExactly(token); // no temp file left behind
        }
    }

    @Test
    void restrictingAnExistingFileTightensIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("gmail-client.json"), "{}");

        SecretFiles.restrictToOwner(file);

        assertOwnerOnly(file);
    }

    @Test
    void gmailLoginStoresTheRefreshTokenOwnerOnly(@TempDir Path dir) throws Exception {
        ObjectMapper m = new ObjectMapper();

        new GoogleOAuth(m, HttpClient.newHttpClient(), dir, null)
                .store(m.readTree("{\"access_token\":\"a\",\"expires_in\":3600}"), "r1");

        Path token = dir.resolve("tokens").resolve("gmail.json");
        assertThat(Files.readString(token)).contains("r1");
        assertOwnerOnly(token);
    }

    @Test
    void existingTokenIsTightenedWhenRead(@TempDir Path dir) throws Exception {
        // a token saved by an earlier version, with the folder's default permissions
        Path token = Files.createDirectories(dir.resolve("tokens")).resolve("gmail.json");
        Files.writeString(token, "{\"refresh_token\":\"old\"}");

        String refresh = new GoogleOAuth(new ObjectMapper(), HttpClient.newHttpClient(), dir, null)
                .storedRefreshToken();

        assertThat(refresh).isEqualTo("old");
        assertOwnerOnly(token);
    }
}
