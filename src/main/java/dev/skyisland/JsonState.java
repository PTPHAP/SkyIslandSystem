package dev.skyisland;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small private records, never paths supplied by an agent. */
final class JsonState {
    static JsonObject read(Path path) {
        if (!Files.exists(path)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception error) { throw new IllegalStateException("治理记录损坏，请恢复备份：" + path.getFileName(), error); }
    }

    static void write(Path path, JsonObject value) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) { throw new IllegalStateException("治理记录无法落盘，未批准继续执行：" + path.getFileName(), error); }
    }
}
