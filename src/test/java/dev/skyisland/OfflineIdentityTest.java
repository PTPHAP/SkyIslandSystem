package dev.skyisland;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineIdentityTest {
    @TempDir Path folder;

    @Test void protectsExistingAccountWithOneTimeClaimAndPasswordAfterRestart() {
        UUID id = OfflineIdentity.offlineUuid("OldPlayer");
        OfflineIdentity store = new OfflineIdentity(folder);
        assertThrows(IllegalArgumentException.class,
            () -> store.register(id, "OldPlayer", "long-unique-password", "", true));
        String claim = store.issueClaim(id);
        assertThrows(IllegalArgumentException.class,
            () -> store.register(id, "OldPlayer", "long-unique-password", "wrong", true));
        store.register(id, "OldPlayer", "long-unique-password", claim, true);
        OfflineIdentity restarted = new OfflineIdentity(folder);
        assertTrue(restarted.registered(id));
        assertTrue(restarted.login(id, "long-unique-password"));
        assertFalse(restarted.login(id, "incorrect-password"));
        assertThrows(IllegalArgumentException.class,
            () -> restarted.register(id, "OldPlayer", "another-password", claim, true));
    }

    @Test void letsNewAccountsRegisterButFailsClosedOnCorruptCredentials() throws Exception {
        UUID id = OfflineIdentity.offlineUuid("NewPlayer");
        OfflineIdentity store = new OfflineIdentity(folder);
        store.register(id, "NewPlayer", "site-only-password", "", false);
        assertTrue(store.login(id, "site-only-password"));
        Files.writeString(folder.resolve("identities.properties"), "user." + id + ".hash=bad\nuser." + id + ".salt=broken\n");
        OfflineIdentity damaged = new OfflineIdentity(folder);
        assertFalse(damaged.available());
        assertThrows(IllegalStateException.class, () -> damaged.login(id, "site-only-password"));
    }

    @Test void passwordChangeRevokesOldPasswordAcrossRestart() {
        UUID id = OfflineIdentity.offlineUuid("NewPlayer");
        OfflineIdentity store = new OfflineIdentity(folder);
        store.register(id, "NewPlayer", "site-only-password", "", false);
        assertThrows(IllegalArgumentException.class,
            () -> store.changePassword(id, "wrong-old-pass", "another-site-password"));
        assertThrows(IllegalArgumentException.class,
            () -> store.changePassword(id, "site-only-password", "short"));
        store.changePassword(id, "site-only-password", "another-site-password");
        OfflineIdentity restarted = new OfflineIdentity(folder);
        assertFalse(restarted.login(id, "site-only-password"));
        assertTrue(restarted.login(id, "another-site-password"));
    }
}
