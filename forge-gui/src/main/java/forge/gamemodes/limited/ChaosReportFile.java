package forge.gamemodes.limited;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded local JSONL output. A failed write disables this sink. */
final class ChaosReportFile {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int TERMINAL_RESERVE = 64 * 1024;
    private static final int MAX_ROW = 256 * 1024;

    private final Path file;
    private final StringBuilder pending = new StringBuilder();
    private int bytes;
    private boolean initialized;
    private boolean failed;
    private boolean limited;

    ChaosReportFile(Path file) {
        this.file = file;
    }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static String json(Object data) {
        return JSON.toJson(data);
    }

    void append(Map<String, Object> row, boolean terminal) {
        initialize();
        if (failed || limited && !terminal) {
            return;
        }
        String line = json(row);
        int size = line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (!terminal && (size > MAX_ROW || bytes + size > MAX_BYTES - TERMINAL_RESERVE)) {
            limited = true;
            Map<String, Object> marker = new LinkedHashMap<>(row);
            marker.keySet().retainAll(java.util.Set.of("draftId", "matchId", "gameId", "seq"));
            marker.put("event", "report_truncated");
            append(marker, true);
            return;
        }
        if (bytes + size <= MAX_BYTES) {
            bytes += size;
            pending.append(line).append('\n');
            if (terminal || pending.length() >= 32 * 1024) {
                flush();
            }
        }
    }

    void appendLegacy(String json, String draftId) {
        initialize();
        if (failed || limited) {
            return;
        }
        String line = "{\"draftId\":\"" + draftId + "\"," + json.substring(1) + "\n";
        int size = line.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_ROW || bytes + size > MAX_BYTES - TERMINAL_RESERVE) {
            limited = true;
            append(fields("event", "report_truncated", "draftId", draftId), true);
            return;
        }
        bytes += size;
        pending.append(line);
        flush();
    }

    void flush() {
        if (failed || pending.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, pending, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            failed = true;
        } finally {
            pending.setLength(0);
        }
    }

    private void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            if (Files.exists(file)) {
                bytes = (int) Math.min(MAX_BYTES, Files.size(file));
                limited = bytes >= MAX_BYTES - TERMINAL_RESERVE;
            }
        } catch (IOException | RuntimeException ex) {
            failed = true;
        }
    }
}
