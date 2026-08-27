package et.elisa.stp.bootstrap;

import org.mobicents.protocols.sctp.fstack.FstackSctpManagementImpl;
import org.mobicents.protocols.sctp.spi.AdaptiveSendController;
import org.mobicents.protocols.sctp.spi.SctpCongestionSample;
import org.mobicents.protocols.sctp.spi.SctpBackend;
import org.mobicents.protocols.sctp.spi.SctpPacketViews;
import org.mobicents.protocols.sctp.spi.SctpProvider;

/**
 * Build-time reachability for Mandrel 25. Keeps the F-Stack Management ctor
 * reachable without Class.forName of Netty/JDK SCTP.
 */
public final class StpNativeHints {
    private StpNativeHints() {}

    public static Class<? extends FstackSctpManagementImpl> defaultManagement() {
        if (!SctpBackend.FSTACK_DPDK.nativeSafe()) {
            throw new IllegalStateException("FSTACK_DPDK must be native-safe");
        }
        return FstackSctpManagementImpl.class;
    }

    public static String defaultImplementation() {
        return SctpProvider.implementationClass(SctpBackend.FSTACK_DPDK);
    }

    /** Keep FFM views reachable without Class.forName of JDK SCTP. */
    public static Class<SctpPacketViews> packetViews() {
        return SctpPacketViews.class;
    }

    public static Class<AdaptiveSendController> adaptiveSend() {
        return AdaptiveSendController.class;
    }

    public static Class<SctpCongestionSample> congestionSample() {
        return SctpCongestionSample.class;
    }
}
