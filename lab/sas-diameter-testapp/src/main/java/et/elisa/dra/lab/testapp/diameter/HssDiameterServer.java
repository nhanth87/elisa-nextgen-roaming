package et.elisa.dra.lab.testapp.diameter;

import java.net.InetAddress;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.gx.GxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.s6a.S6aProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.swx.SwxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.sh.ShProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.rx.RxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.cxdx.CxDxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.s13.S13ProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.slh.SlhProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.slg.SlgProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.s6c.S6cProviderImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;

import et.elisa.dra.lab.testapp.HssSimulator;

public final class HssDiameterServer {

    private static final Logger LOG = LogManager.getLogger(HssDiameterServer.class);

    private static final String LINK_ID = "hss-sim";

    private final HssSimulator hss;
    private final String bindAddress;
    private final int diameterPort;
    private final boolean sctp;
    private final String originHost;
    private final String originRealm;
    private final String peerHost;
    private final String peerRealm;

    private WorkerPool workerPool;
    private DiameterStack stack;
    private final IDGenerator<?> generator = new UUIDGenerator();

    public HssDiameterServer(HssSimulator hss, String bindAddress, int diameterPort, boolean sctp,
            String originHost, String originRealm, String peerHost, String peerRealm) {
        this.hss = hss;
        this.bindAddress = bindAddress;
        this.diameterPort = diameterPort;
        this.sctp = sctp;
        this.originHost = originHost;
        this.originRealm = originRealm;
        this.peerHost = peerHost;
        this.peerRealm = peerRealm;
    }

    public void start() throws Exception {
        workerPool = new WorkerPool("HSS-SIM");
        workerPool.start(4);
        stack = new DiameterStackImpl(getClass().getClassLoader(), generator, workerPool, 4,
                originHost, "HSS Simulator", 0L, 10L,
                10_000L, 2_000L, 5_000L, 5_000L, 5_000L);
        stack.getNetworkManager().addLink(LINK_ID,
                InetAddress.getByName(bindAddress), 0,
                InetAddress.getByName(bindAddress), diameterPort,
                true, sctp, originHost, originRealm, peerHost, peerRealm, false);

        List<VendorSpecificApplicationId> noVendor = List.of();

        // S6a
        Package s6aCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.s6a",
                com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest.class);
        Package s6aImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.s6a",
                com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.S6A), List.of(), s6aCommands, s6aImpl);

        // SWx
        Package swxCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.swx",
                com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthRequest.class);
        Package swxImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.swx",
                com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.SWX), List.of(), swxCommands, swxImpl);

        // Gx
        Package gxCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.gx",
                com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlRequest.class);
        Package gxImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.gx",
                com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.GX), List.of(), gxCommands, gxImpl);

        // Sh
        Package shCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.sh",
                com.mobius.software.telco.protocols.diameter.commands.sh.UserDataRequest.class);
        Package shImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.sh",
                com.mobius.software.telco.protocols.diameter.impl.commands.sh.UserDataRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.SH), List.of(), shCommands, shImpl);

        // Rx
        Package rxCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.rx",
                com.mobius.software.telco.protocols.diameter.commands.rx.AARequest.class);
        Package rxImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.rx",
                com.mobius.software.telco.protocols.diameter.impl.commands.rx.AARequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.RX), List.of(), rxCommands, rxImpl);

        // Cx/Dx
        Package cxdxCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.cxdx",
                com.mobius.software.telco.protocols.diameter.commands.cxdx.UserAuthorizationRequest.class);
        Package cxdxImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.cxdx",
                com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.CX_DX), List.of(), cxdxCommands, cxdxImpl);

        // S13
        Package s13Commands = pkg("com.mobius.software.telco.protocols.diameter.commands.s13",
                com.mobius.software.telco.protocols.diameter.commands.s13.MEIdentityCheckRequest.class);
        Package s13Impl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.s13",
                com.mobius.software.telco.protocols.diameter.impl.commands.s13.MEIdentityCheckRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.S13), List.of(), s13Commands, s13Impl);

        // SLh
        Package slhCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.slh",
                com.mobius.software.telco.protocols.diameter.commands.slh.LCSRoutingInfoRequest.class);
        Package slhImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.slh",
                com.mobius.software.telco.protocols.diameter.impl.commands.slh.LCSRoutingInfoRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.SLH), List.of(), slhCommands, slhImpl);

        // SLg
        Package slgCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.slg",
                com.mobius.software.telco.protocols.diameter.commands.slg.ProvideLocationRequest.class);
        Package slgImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.slg",
                com.mobius.software.telco.protocols.diameter.impl.commands.slg.ProvideLocationRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.SLG), List.of(), slgCommands, slgImpl);

        // S6c
        Package s6cCommands = pkg("com.mobius.software.telco.protocols.diameter.commands.s6c",
                com.mobius.software.telco.protocols.diameter.commands.s6c.SendRoutingInfoForSMRequest.class);
        Package s6cImpl = pkg("com.mobius.software.telco.protocols.diameter.impl.commands.s6c",
                com.mobius.software.telco.protocols.diameter.impl.commands.s6c.SendRoutingInfoForSMRequestImpl.class);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.S6C), List.of(), s6cCommands, s6cImpl);

        stack.getNetworkManager().startLink(LINK_ID);

        // Wire handlers
        S6aProviderImpl s6aProvider =
                (S6aProviderImpl) stack.getProvider((long) ApplicationIDs.S6A, s6aCommands);
        s6aProvider.setServerListener(generator.generateID(),
                new S6aHandler(hss, s6aProvider.getMessageFactory(), s6aProvider.getAvpFactory()));

        SwxProviderImpl swxProvider =
                (SwxProviderImpl) stack.getProvider((long) ApplicationIDs.SWX, swxCommands);
        swxProvider.setServerListener(generator.generateID(),
                new SwxHandler(hss, swxProvider.getMessageFactory(), swxProvider.getAvpFactory()));

        GxProviderImpl gxProvider =
                (GxProviderImpl) stack.getProvider((long) ApplicationIDs.GX, gxCommands);
        gxProvider.setServerListener(generator.generateID(),
                new GxHandler(hss, gxProvider.getMessageFactory()));

        ShProviderImpl shProvider =
                (ShProviderImpl) stack.getProvider((long) ApplicationIDs.SH, shCommands);
        shProvider.setServerListener(generator.generateID(),
                new ShHandler(hss, shProvider.getMessageFactory()));

        RxProviderImpl rxProvider =
                (RxProviderImpl) stack.getProvider((long) ApplicationIDs.RX, rxCommands);
        rxProvider.setServerListener(generator.generateID(),
                new RxHandler(hss, rxProvider.getMessageFactory()));

        CxDxProviderImpl cxdxProvider =
                (CxDxProviderImpl) stack.getProvider((long) ApplicationIDs.CX_DX, cxdxCommands);
        cxdxProvider.setServerListener(generator.generateID(),
                new CxDxHandler(hss, cxdxProvider.getMessageFactory()));

        S13ProviderImpl s13Provider =
                (S13ProviderImpl) stack.getProvider((long) ApplicationIDs.S13, s13Commands);
        s13Provider.setServerListener(generator.generateID(),
                new S13Handler(hss, s13Provider.getMessageFactory()));

        SlhProviderImpl slhProvider =
                (SlhProviderImpl) stack.getProvider((long) ApplicationIDs.SLH, slhCommands);
        slhProvider.setServerListener(generator.generateID(),
                new SLhHandler(hss, slhProvider.getMessageFactory()));

        SlgProviderImpl slgProvider =
                (SlgProviderImpl) stack.getProvider((long) ApplicationIDs.SLG, slgCommands);
        slgProvider.setServerListener(generator.generateID(),
                new SLgHandler(hss, slgProvider.getMessageFactory()));

        S6cProviderImpl s6cProvider =
                (S6cProviderImpl) stack.getProvider((long) ApplicationIDs.S6C, s6cCommands);
        s6cProvider.setServerListener(generator.generateID(),
                new S6cHandler(hss, s6cProvider.getMessageFactory()));

        LOG.info("HSS Diameter listening on {}:{} transport={} origin={}/{} peer={}/{} [10 apps]",
                bindAddress, diameterPort, sctp ? "sctp" : "tcp", originHost, originRealm,
                peerHost, peerRealm);
    }

    public void stop() {
        if (stack != null) {
            try {
                stack.stop();
            } catch (Exception e) {
                LOG.warn("HSS stack stop", e);
            }
            stack = null;
        }
        if (workerPool != null) {
            workerPool.stop();
            workerPool = null;
        }
    }

    public boolean isListening() {
        return stack != null;
    }

    private static Package pkg(String name, Class<?> anchor) {
        @SuppressWarnings("unused")
        Class<?> loaded = anchor;
        Package p = Package.getPackage(name);
        if (p == null) {
            throw new IllegalStateException("package not loaded: " + name);
        }
        return p;
    }
}
