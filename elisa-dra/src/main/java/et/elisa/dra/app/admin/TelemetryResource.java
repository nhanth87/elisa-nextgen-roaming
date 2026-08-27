package et.elisa.dra.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/telemetry")
@ApplicationScoped
public class TelemetryResource {

    private final AdminPort admin;

    @Inject
    public TelemetryResource(AdminPort admin) {
        this.admin = admin;
    }

    public TelemetryResource() {
        this(AdminPort.NOOP);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> telemetry() {
        TelemetryPort port = admin.telemetry();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", port != null && port.live());
        out.put("counters", port == null ? Map.of() : port.snapshot());
        return out;
    }
}
