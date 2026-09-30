package dev.skyisland;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks backup structure without reading a whole archive on the server thread. */
final class BackupVerifier {
    static boolean recent(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        try (var entries = Files.list(root)) {
            return entries.anyMatch(path -> recent(path, cutoff));
        } catch (IOException ignored) { return false; }
    }

    static boolean recent(Path path, Instant cutoff) {
        try {
            if (!Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) return false;
            if (Files.isDirectory(path)) {
                if (!Files.isRegularFile(path.resolve("level.dat"))) return false;
                try (var regions = Files.list(path.resolve("region"))) {
                    return regions.anyMatch(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".mca"));
                }
            }
            if (!path.getFileName().toString().toLowerCase().endsWith(".zip") || Files.size(path) < 128) return false;
            try (ZipFile archive = new ZipFile(path.toFile())) {
                boolean level = false, region = false;
                Enumeration<? extends ZipEntry> contents = archive.entries();
                while (contents.hasMoreElements()) {
                    ZipEntry entry = contents.nextElement();
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || entry.getSize() == 0) continue;
                    if (name.equals("level.dat") || name.endsWith("/level.dat")) level = true;
                    if (name.contains("/region/") && name.endsWith(".mca") || name.startsWith("region/") && name.endsWith(".mca")) region = true;
                }
                return level && region;
            }
        } catch (IOException ignored) { return false; }
    }
}
