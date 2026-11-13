package com.emailagent.mailbox;

import io.quarkus.logging.Log;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Files only the owner's account may read: the Gmail refresh token (it opens the whole mailbox) and the OAuth client
 * file (it holds the client secret). Design doc §13, access control: on a shared PC, another account must not be able
 * to copy them. POSIX permissions where they exist, otherwise a Windows ACL naming the current user alone.
 */
final class SecretFiles {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private SecretFiles() {}

    /**
     * Writes the file so that it is never readable by anyone else, not even for a moment: the content goes into an
     * owner-only temp file next to it, which then replaces the target in one move.
     */
    static void writeOwnerOnly(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        createOwnerOnlyDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".secret", ".tmp");
        try {
            restrictToOwner(tmp);
            Files.writeString(tmp, content);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Leaves the file or folder readable and writable by the current user only. */
    static void restrictToOwner(Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal me = currentUser(FileSystems.getDefault().getUserPrincipalLookupService(),
                    System.getenv("USERDOMAIN"), System.getProperty("user.name"), path);
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(me)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(entry.build()));
            return;
        }
        if (WARNED.compareAndSet(false, true)) {
            Log.warn("this file system has no permissions to set: the Gmail secrets are protected only by the folder");
        }
    }

    /**
     * The account the application runs as. The current user rather than the file's owner, because an administrator's
     * new files can belong to the Administrators group, which a normal (non-elevated) process can't use. Tried as
     * "DOMAIN\\user" first (an Entra ID account often resolves only that way), then the bare name; the file's owner is
     * the last resort, so a name Windows can't resolve never blocks saving the Gmail login.
     */
    static UserPrincipal currentUser(UserPrincipalLookupService lookup, String domain, String user, Path fallback)
            throws IOException {
        List<String> names = domain == null || domain.isBlank() ? List.of(user) : List.of(domain + "\\" + user, user);
        for (String name : names) {
            try {
                return lookup.lookupPrincipalByName(name);
            } catch (UserPrincipalNotFoundException e) {
                // try the next form of the name
            }
        }
        return Files.getOwner(fallback);
    }

    /** Creates the missing folders of the path, each one owner-only; folders that already exist are left alone. */
    private static void createOwnerOnlyDirectories(Path dir) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path p = dir; p != null && !Files.exists(p); p = p.getParent()) {
            missing.push(p);
        }
        while (!missing.isEmpty()) {
            Path p = missing.pop();
            Files.createDirectory(p);
            restrictToOwner(p);
        }
    }
}
