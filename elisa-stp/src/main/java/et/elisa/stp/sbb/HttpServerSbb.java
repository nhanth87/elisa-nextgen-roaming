package et.elisa.stp.sbb;

import et.elisa.stp.admin.AdminHttpHandler;
import et.elisa.stp.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Admin/health HTTP surface (DESIGN §5 observe plane). Adapts the
 * ra-http-server request model into the framework-agnostic
 * {@link AdminHttpHandler#tryHandle} router and replies over the injected
 * {@code http-server-ra} command port. Sync parks the HTTP session — it never
 * sleeps or blocks the SLEE event thread.
 */
public final class HttpServerSbb implements Sbb, SleeEventHandler {
    private final SbbServices services;

    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort http;

    public HttpServerSbb() {
        this(null);
    }

    public HttpServerSbb(SbbServices services) {
        this.services = services;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof HttpWebRequestEvent request)) {
            return;
        }
        try {
            svc().bindHttp(http);
            Optional<AdminHttpHandler.HttpReply> reply = svc().adminHttp().tryHandle(
                    request.getMethod() == null ? "" : request.getMethod(),
                    request.getPath() == null ? "/" : request.getPath(),
                    request.getHeaders(), request.getQueryParams(), request.getBody(),
                    request.getUploads());
            if (reply.isPresent()) {
                replyAdmin(request.getSessionId(), reply.get());
            } else {
                replyText(request.getSessionId(), 404, "not found");
            }
        } catch (Throwable failure) {
            replyText(request.getSessionId(), 500, "internal error");
        }
    }

    private void replyAdmin(String sessionId, AdminHttpHandler.HttpReply reply) {
        RaCommandPort port = svc().http();
        if (port == null) return;
        String text = null;
        byte[] binary = reply.body();
        String ct = reply.contentType();
        if (ct != null && (ct.startsWith("text/") || ct.contains("json") || ct.contains("javascript"))) {
            text = new String(binary == null ? new byte[0] : binary, StandardCharsets.UTF_8);
            binary = null;
        }
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                sessionId, reply.status(), ct, text, binary,
                reply.headers() == null ? Map.of() : reply.headers()));
    }

    private void replyText(String sessionId, int status, String body) {
        RaCommandPort port = svc().http();
        if (port == null) return;
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                sessionId, status, "text/plain; charset=utf-8", body, null, Map.of()));
    }

    private SbbServices svc() {
        return services == null ? SbbServices.get() : services;
    }
}
