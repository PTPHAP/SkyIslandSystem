package dev.skyisland;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Passwords protect offline UUID accounts, but cannot stop a player creating another name. */
final class OfflineIdentity {
    private static final int ITERATIONS = 210_000;
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final Properties data = new Properties();
    private final boolean available;

    OfflineIdentity(Path folder) {
        file = folder.resolve("identities.properties");
        boolean valid = true;
        if (Files.exists(file)) try (InputStream in = Files.newInputStream(file)) {
            data.load(in);
            for (String key : data.stringPropertyNames()) {
                if (key.startsWith("user.") && key.endsWith(".hash")) {
                    String prefix = key.substring(0, key.length() - 5);
                    UUID.fromString(prefix.substring(5));
                    if (HexFormat.of().parseHex(data.getProperty(key)).length != 32)
                        throw new IllegalArgumentException("hash 无效");
                    byte[] salt = HexFormat.of().parseHex(data.getProperty(prefix + ".salt"));
                    if (salt.length != 16 || !data.getProperty(prefix + ".name", "").matches("[A-Za-z0-9_]{3,16}"))
                        throw new IllegalArgumentException("身份记录无效");
                }
                if (key.startsWith("claim.") && HexFormat.of().parseHex(data.getProperty(key)).length != 32)
                    throw new IllegalArgumentException("认领码记录无效");
            }
        } catch (Exception failure) { valid = false; }
        available = valid;
    }

    boolean available() { return available; }

    synchronized boolean registered(UUID id) {
        requireAvailable();
        return data.containsKey("user." + id + ".hash");
    }

    synchronized String issueClaim(UUID id) {
        requireAvailable();
        if (registered(id)) throw new IllegalArgumentException("该账号已经注册");
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String code = HexFormat.of().formatHex(bytes);
        Properties previous = copy();
        data.setProperty("claim." + id, digest(code));
        saveOrRollback(previous);
        return code;
    }

    void register(UUID id, String name, String password, String claim, boolean legacy) {
        requireAvailable();
        if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("玩家名无效");
        validatePassword(name, password);
        synchronized (this) { requireClaim(id, claim, legacy); }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String encrypted = hash(password, salt);
        synchronized (this) {
            requireClaim(id, claim, legacy);
            Properties previous = copy();
            String prefix = "user." + id;
            data.setProperty(prefix + ".salt", HexFormat.of().formatHex(salt));
            data.setProperty(prefix + ".hash", encrypted);
            data.setProperty(prefix + ".name", name);
            data.remove("claim." + id);
            saveOrRollback(previous);
        }
    }

    boolean login(UUID id, String password) {
        requireAvailable();
        String prefix = "user." + id;
        String expected;
        byte[] salt;
        synchronized (this) {
            expected = data.getProperty(prefix + ".hash");
            if (expected == null) return false;
            salt = HexFormat.of().parseHex(data.getProperty(prefix + ".salt"));
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            hash(password, salt).getBytes(StandardCharsets.US_ASCII));
    }

    void changePassword(UUID id, String oldPassword, String newPassword) {
        requireAvailable();
        String prefix = "user." + id;
        String previousHash;
        String name;
        synchronized (this) {
            previousHash = data.getProperty(prefix + ".hash");
            name = data.getProperty(prefix + ".name", "");
        }
        if (previousHash == null || !login(id, oldPassword)) throw new IllegalArgumentException("原密码错误");
        validatePassword(name, newPassword);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String encrypted = hash(newPassword, salt);
        synchronized (this) {
            if (!previousHash.equals(data.getProperty(prefix + ".hash")))
                throw new IllegalStateException("密码已在另一会话改变，请重新登录");
            Properties previous = copy();
            data.setProperty(prefix + ".salt", HexFormat.of().formatHex(salt));
            data.setProperty(prefix + ".hash", encrypted);
            saveOrRollback(previous);
        }
    }

    static UUID offlineUuid(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("玩家名无效");
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void requireClaim(UUID id, String claim, boolean legacy) {
        if (registered(id)) throw new IllegalArgumentException("账号已经注册，请使用 login");
        String stored = data.getProperty("claim." + id);
        if ((legacy || stored != null) && (stored == null || !MessageDigest.isEqual(
            stored.getBytes(StandardCharsets.US_ASCII),
            digest(claim == null ? "" : claim).getBytes(StandardCharsets.US_ASCII))))
            throw new IllegalArgumentException("旧玩家或已预留账号需要有效认领码");
    }

    private static void validatePassword(String name, String password) {
        if (password == null || password.length() < 12 || password.length() > 64
            || password.chars().anyMatch(Character::isWhitespace) || password.equalsIgnoreCase(name))
            throw new IllegalArgumentException("密码须为 12-64 字、不能含空格或与玩家名相同");
    }

    private void requireAvailable() {
        if (!available) throw new IllegalStateException("身份记录损坏，拒绝离线账号登录；请恢复 identities.properties");
    }

    private static String hash(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try { return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch (Exception failure) { throw new IllegalStateException("密码哈希失败", failure); }
        finally { spec.clearPassword(); }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private Properties copy() {
        Properties previous = new Properties();
        previous.putAll(data);
        return previous;
    }

    private void saveOrRollback(Properties previous) {
        try { save(); }
        catch (RuntimeException failure) {
            data.clear();
            data.putAll(previous);
            throw failure;
        }
    }

    private void save() {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) { data.store(out, "SkyIslandSystem offline identities"); }
        catch (Exception error) { throw new IllegalStateException("无法保存身份记录", error); }
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception error) { throw new IllegalStateException("无法保存身份记录", error); }
        } catch (Exception error) { throw new IllegalStateException("无法保存身份记录", error); }
    }
}
