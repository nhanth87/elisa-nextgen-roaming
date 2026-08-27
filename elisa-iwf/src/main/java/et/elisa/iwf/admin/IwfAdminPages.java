package et.elisa.iwf.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
@Path("/admin")
public class IwfAdminPages {

    @Inject
    IwfAdminPageHandler handler;

    @GET
    @Path("/")
    public Response index() {
        return serve("GET", "/admin/", null, null);
    }

    @GET
    @Path("/ss7")
    public Response ss7() {
        return serve("GET", "/admin/ss7", null, null);
    }

    @GET
    @Path("/diameter")
    public Response diameter() {
        return serve("GET", "/admin/diameter", null, null);
    }

    @GET
    @Path("/routing")
    public Response routing() {
        return serve("GET", "/admin/routing", null, null);
    }

    @GET
    @Path("/telemetry")
    public Response telemetry() {
        return serve("GET", "/admin/telemetry", null, null);
    }

    @POST
    @Path("/ss7")
    public Response ss7Post(String body) {
        return serve("POST", "/admin/ss7", null, body);
    }

    @POST
    @Path("/diameter")
    public Response diameterPost(String body) {
        return serve("POST", "/admin/diameter", null, body);
    }

    private Response serve(String method, String path, Map<String, String> query, String body) {
        var reply = handler.tryHandle(method, path, query, body);
        if (reply.isEmpty()) {
            return Response.status(404).build();
        }
        IwfAdminPageHandler.HttpReply r = reply.get();
        return Response.status(r.status())
                .type(r.contentType())
                .entity(r.body())
                .build();
    }
}
