package et.elisa.iwf.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/admin/static")
public class IwfStaticResource {

    @Inject
    AdminPageRenderer pages;

    @GET
    @Path("/{file: .+}")
    public Response serve(@PathParam("file") String file) {
        try {
            byte[] raw = pages.staticResource(file);
            if (raw == null) {
                return Response.status(404).build();
            }
            return Response.ok(raw, pages.staticContentType(file)).build();
        } catch (Exception e) {
            return Response.status(404).build();
        }
    }
}
