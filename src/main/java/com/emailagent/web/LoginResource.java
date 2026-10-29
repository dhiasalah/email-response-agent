package com.emailagent.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** The login form. It posts to Quarkus's form login (/j_security_check); the check is OwnerIdentityProvider's. */
@Path("/login")
public class LoginResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(boolean error);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("error") boolean error) {
        return Templates.login(error);
    }
}
