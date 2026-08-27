package et.elisa.iwf.admin;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.IwfConfigJson;
import et.elisa.iwf.bootstrap.IwfBootstrap;
import et.elisa.iwf.map.NoopMapLeg;
import et.elisa.iwf.mapping.Ts29305Table;
import et.elisa.iwf.telemetry.IwfKpi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/iwf")
@ApplicationScoped
public class IwfAdminResource {

    @Inject
    IwfBootstrap bootstrap;

    @Inject
    LinkStatusService linkStatus;

    @GET
    @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "iwf-microjainslee");
        out.put("mappings", Ts29305Table.all().size());
        var leg = bootstrap.diameterLeg();
        out.put("diaLegReady", leg.ready());
        out.put("diameter", leg instanceof et.elisa.iwf.diameter.CorsacDiameterLeg corsac
                ? corsac.healthSnapshot()
                : Map.of("open", false));
        out.put("mapLegReady", !(bootstrap.mapLeg() instanceof NoopMapLeg));
        out.put("links", linkStatus.snapshot());
        return out;
    }

    @GET
    @Path("mappings")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> mappings() {
        return Ts29305Table.all().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("mapOp", e.mapOp().name());
                    m.put("mapOpCode", e.mapOp().opCode());
                    m.put("diaOp", e.diaCmd().name());
                    m.put("diaCmd", e.diaCmd().cmdCode());
                    m.put("diaApp", e.diaCmd().app().diaName());
                    m.put("mapToDia", e.mapToDia());
                    m.put("diaToMap", e.diaToMap());
                    m.put("specRef", e.specRef());
                    m.put("status", e.status().name());
                    m.put("transforms", e.transforms().stream()
                            .map(t -> Map.of(
                                    "mapSource", t.mapSource(),
                                    "diaAvp", t.diaAvpName(),
                                    "diaAvpCode", t.diaAvpCode(),
                                    "kind", t.kind().name(),
                                    "required", t.required()))
                            .toList());
                    return m;
                })
                .toList();
    }

    @GET
    @Path("config")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> config() {
        IwfConfig c = IwfConfigJson.load();
        return IwfConfig.asMap(c);
    }

    @GET
    @Path("kpi")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Long> kpi() {
        return IwfKpi.snapshot();
    }

    @GET
    @Path("links")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> links() {
        return linkStatus.snapshot();
    }
}
