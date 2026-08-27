package et.elisa.signaling;

import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

public final class OverloadPolicy {

    public static final int MIN_PRIORITY = 0;
    public static final int MAX_PRIORITY = 15;
    public static final int DEFAULT_PRIORITY = 10;
    public static final int TIERS = MAX_PRIORITY + 1;

    private final LongAdder admitted = new LongAdder();
    private final LongAdder throttled = new LongAdder();

    private OverloadPolicy() {
    }

    public static OverloadPolicy create() {
        return new OverloadPolicy();
    }

    public static int clamp(int drmpPriority) {
        if (drmpPriority < MIN_PRIORITY || drmpPriority > MAX_PRIORITY) {
            return DEFAULT_PRIORITY;
        }
        return drmpPriority;
    }

    public static int throttleOrder(int drmpPriority) {
        return MAX_PRIORITY - clamp(drmpPriority);
    }

    public static boolean isCriticalCommand(int cmdCode, Set<Integer> criticalSet) {
        return criticalSet != null && criticalSet.contains(cmdCode);
    }

    public long admittedCount() {
        return admitted.sum();
    }

    public long throttledCount() {
        return throttled.sum();
    }

    public void recordAdmitted() {
        admitted.increment();
    }

    public void recordThrottled() {
        throttled.increment();
    }
}
