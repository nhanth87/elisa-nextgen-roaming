package et.elisa.stp.config;

import java.nio.file.Path;

import org.restcomm.protocols.ss7.config.Ss7Config;

/**
 * Stamps SCTP System properties from the SS7 JSON document. F-Stack Management
 * reads System properties (not MicroProfile), so this must run before the RA
 * constructs {@code FstackSctpManagementImpl}. JSON values overwrite {@code -D};
 * omitted JSON fields leave existing properties (run.sh / application.properties).
 */
public final class SctpJsonStamp {
    private SctpJsonStamp() {}

    public static void overwriteFromJson(Ss7Config cfg) {
        if (cfg == null || cfg.sctp() == null) {
            return;
        }
        Ss7Config.Sctp s = cfg.sctp();
        put(s.backend(), "sctp.backend");
        put(s.mode(), "sctp.fstack.mode");
        put(s.dataplane(), "sctp.fstack.dataplane");
        if (s.library() != null && !s.library().isBlank()) {
            Path lib = Path.of(s.library().trim());
            if (!lib.isAbsolute()) {
                lib = Path.of("").toAbsolutePath().resolve(lib);
            }
            System.setProperty("sctp.fstack.library", lib.toString());
        }
        Boolean inProcess = s.inProcess();
        if (inProcess == null && s.mode() != null && !s.mode().isBlank()) {
            inProcess = "IN_PROCESS".equalsIgnoreCase(s.mode().trim().replace('-', '_'));
        }
        if (inProcess != null) {
            System.setProperty("sctp.fstack.inprocess.enabled", Boolean.toString(inProcess));
        }
    }

    public static void fallbackIfAbsent(String backend, String mode, String dataplane,
                                        boolean inProcess, String library) {
        putIfAbsent("sctp.backend", backend);
        putIfAbsent("sctp.fstack.mode", mode);
        putIfAbsent("sctp.fstack.dataplane", dataplane);
        putIfAbsent("sctp.fstack.inprocess.enabled", Boolean.toString(inProcess));
        if (library != null && !library.isBlank()) {
            Path lib = Path.of(library.trim());
            if (!lib.isAbsolute()) {
                lib = Path.of("").toAbsolutePath().resolve(lib);
            }
            putIfAbsent("sctp.fstack.library", lib.toString());
        }
    }

    private static void put(String value, String key) {
        if (value != null && !value.isBlank()) {
            System.setProperty(key, value.trim());
        }
    }

    private static void putIfAbsent(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String existing = System.getProperty(key);
        if (existing == null || existing.isBlank()) {
            System.setProperty(key, value);
        }
    }
}
