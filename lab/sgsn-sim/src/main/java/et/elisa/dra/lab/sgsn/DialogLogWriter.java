package et.elisa.dra.lab.sgsn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only oracle log: one JSONL record per MAP dialog outcome.
 * The L3 oracle ({@code docs/plans/iwf-e2e-test-plan.md} §6.1) polls this
 * file to decide PASS/FAIL. The schema is intentionally minimal so the
 * oracle can be a {@code grep} pipeline.
 *
 * <p>Record shape:
 * <pre>
 * { "ts":"2026-08-27T14:00:00Z", "imsi":"4520402001", "mapOp":"updateGprsLocation",
 *   "opCode":48, "result":"OK", "resultCode":2001, "rttMs":142,
 *   "dialogIdLocal":123, "dialogIdRemote":456, "error":null,
 *   "imsiSeen":true, "calledAddress":"8860123456001", "calledPc":250, "calledSsn":11 }
 * </pre>
 */
public final class DialogLogWriter {

    public enum Result { OK, MAP_ERROR, TIMEOUT, STACK_ERROR, UNKNOWN }

    private final Path path;
    private final AtomicLong seq = new AtomicLong();

    public DialogLogWriter(Path path) throws IOException {
        this.path = path;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }

    public void append(String imsi, String mapOp, int opCode,
                       Result result, Integer resultCode, long rttMs,
                       Long dialogIdLocal, Long dialogIdRemote, String error,
                       boolean imsiSeen, String calledAddress,
                       int calledPc, int calledSsn) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"ts\":\"").append(Instant.now().toString()).append("\",")
          .append("\"seq\":").append(seq.incrementAndGet()).append(',')
          .append("\"imsi\":").append(json(imsi)).append(',')
          .append("\"mapOp\":").append(json(mapOp)).append(',')
          .append("\"opCode\":").append(opCode).append(',')
          .append("\"result\":\"").append(result).append("\",")
          .append("\"resultCode\":").append(resultCode == null ? "null" : resultCode).append(',')
          .append("\"rttMs\":").append(rttMs).append(',')
          .append("\"dialogIdLocal\":").append(dialogIdLocal == null ? "null" : dialogIdLocal).append(',')
          .append("\"dialogIdRemote\":").append(dialogIdRemote == null ? "null" : dialogIdRemote).append(',')
          .append("\"error\":").append(error == null ? "null" : json(error)).append(',')
          .append("\"imsiSeen\":").append(imsiSeen).append(',')
          .append("\"calledAddress\":").append(json(calledAddress)).append(',')
          .append("\"calledPc\":").append(calledPc).append(',')
          .append("\"calledSsn\":").append(calledSsn)
          .append("}\n");
        try {
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("DialogLogWriter.append failed: " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static List<String> tail(Path path, int n) throws IOException {
        if (!Files.exists(path)) return List.of();
        List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = Math.max(0, all.size() - n);
        return new ArrayList<>(all.subList(from, all.size()));
    }
}
