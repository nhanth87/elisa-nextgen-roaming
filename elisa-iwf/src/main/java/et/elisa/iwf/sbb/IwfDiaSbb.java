package et.elisa.iwf.sbb;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;

import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;

import et.elisa.iwf.ra.IwfAnswerEvent;
import et.elisa.iwf.ra.IwfRequestEvent;

/**
 * IWF Diameter SBB: container-dispatched handler for leg answers and
 * server-initiated requests. Thin — all logic lives in {@link IwfRelayCore}.
 */
@SbbAnnotation(name = "IwfDiaSbb", vendor = "et.elisa", version = "1.0")
public abstract class IwfDiaSbb extends CmpBackedSbb implements SleeEventHandler {

    @InjectRa(name = "iwf-diameter-ra")
    private volatile RaCommandPort diameterRa;

    private final IwfRelayCore core;

    public IwfDiaSbb() {
        this(null);
    }

    public IwfDiaSbb(IwfRelayCore core) {
        this.core = core;
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @Override
    public void sbbCreate() {
        bindRa();
    }

    @Override
    public void sbbPostCreate() {
        bindRa();
    }

    @Override
    public void sbbActivate() {
        bindRa();
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bindRa();
        if (core == null) {
            return;
        }
        switch (event) {
            case IwfAnswerEvent ans -> {
                setSessionId(sessionIdOf(ans.msg()));
                core.onAnswer(ans.linkId(), ans.hopByHopId(), ans.msg());
            }
            case IwfRequestEvent req -> {
                setSessionId(sessionIdOf(req.msg()));
                core.onRequest(req.linkId(), req.msg());
            }
            default -> {
            }
        }
    }

    private static String sessionIdOf(Object msg) {
        try {
            if (msg instanceof DiameterRequest req) {
                String s = req.getSessionId();
                return s == null ? "" : s;
            }
            if (msg instanceof DiameterAnswer ans) {
                String s = ans.getSessionId();
                return s == null ? "" : s;
            }
        } catch (Exception ignored) {
            // sessionless frames
        }
        return "";
    }

    private void bindRa() {
        if (diameterRa != null && core != null) {
            core.bindCommandPort(diameterRa);
        }
    }

    public static final class $Concrete extends IwfDiaSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(IwfRelayCore core) {
            super(core);
        }

        @Override
        public String getSessionId() {
            return str("sessionId");
        }

        @Override
        public void setSessionId(String v) {
            put("sessionId", v);
            write("setSessionId", String.class, v);
        }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException ignored) {
                // local map when container store unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return IwfDiaSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
