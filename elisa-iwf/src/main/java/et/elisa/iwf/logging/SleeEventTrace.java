package et.elisa.iwf.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SBB event tracing utility — logs entry/exit of each SBB event handler
 * at TRACE level for debugging without polluting INFO/DEBUG logs.
 *
 * <p>Pattern from GMLC {@code SleeEventTrace}. Correlation ID extracted
 * from the event if available.</p>
 */
public final class SleeEventTrace {

    private static final Logger LOG = LogManager.getLogger(SleeEventTrace.class);

    private SleeEventTrace() {
    }

    /** Log SBB event entry. */
    public static void inSbb(String sbbName, Object event) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("[{}] in: {}", sbbName, shortDesc(event));
        }
    }

    /** Log SBB event exit with detail. */
    public static void outSbb(String sbbName, Object event, String detail) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("[{}] out: {} -> {}", sbbName, shortDesc(event), detail);
        }
    }

    private static String shortDesc(Object event) {
        if (event == null) {
            return "null";
        }
        String cls = event.getClass().getSimpleName();
        return cls.length() > 60 ? cls.substring(0, 57) + "..." : cls;
    }
}
