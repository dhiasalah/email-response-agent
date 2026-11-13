package com.emailagent.web;

import com.emailagent.model.ScopeChoice;
import com.emailagent.orchestrator.BroadcastRun;
import com.emailagent.orchestrator.CardAnswers;
import com.emailagent.orchestrator.OwnerActions;
import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.jboss.resteasy.reactive.RestForm;

/**
 * The owner's review panel (design doc §10): three queues and the actions on them. Every action is a POST from a
 * form carrying a CSRF token, answered with a redirect (no resubmission on reload). The panel decides nothing itself:
 * it calls the same services and orchestrator steps as the rest of the system, which check everything again.
 */
@Path("/panel")
@RolesAllowed(OwnerIdentityProvider.ROLE)
public class PanelResource {

    /** Feedback after an action: fixed texts chosen by code; a code from the URL that isn't listed shows nothing. */
    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("answered", "Card answered: its emails move on to the writer."),
            Map.entry("card_changed", "The card changed since you saw it (a question joined or left). Check it again."),
            Map.entry("bad_value", "That answer doesn't fit the card (expected format shown on the card)."),
            Map.entry("sent", "Reply queued for sending."),
            Map.entry("sent_all", "Replies of the card queued for sending (outliers excluded)."),
            Map.entry("rejected", "Draft rejected: the email is in your escalations."),
            Map.entry("closed", "Escalation closed."),
            Map.entry("sent_edited", "Your reply is queued for sending."),
            Map.entry("bad_reply", "The reply is empty or too long (6000 characters at most)."),
            Map.entry("forgot", "Forgotten: the next sender who asks gets a card again."),
            Map.entry("retried", "The writer will try again; the new draft comes back here for review."),
            Map.entry("broadcast", "Broadcast approved and queued for the cohort list."),
            Map.entry("nothing", "Nothing changed: it was already handled."));

    /** Codes shown as a warning, not a confirmation: the action didn't go through. */
    static final Set<String> ERRORS = Set.of("card_changed", "bad_value", "bad_reply");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance panel(PanelQueries.Dashboard dash, String message, boolean error);

        static native TemplateInstance metrics(MetricsQueries.Metrics m);

        static native TemplateInstance memory(InsightQueries.Memory mem, String message);

        static native TemplateInstance activity(InsightQueries.Activity act);

        static native TemplateInstance agents(AgentQueries.Page page);
    }

    private final PanelQueries queries;
    private final MetricsQueries metrics;
    private final CardAnswers cardAnswers;
    private final OwnerActions actions;
    private final BroadcastRun broadcasts;
    private final InsightQueries insights;
    private final AgentQueries agents;

    public PanelResource(PanelQueries queries, MetricsQueries metrics, CardAnswers cardAnswers,
            OwnerActions actions, BroadcastRun broadcasts, InsightQueries insights, AgentQueries agents) {
        this.insights = insights;
        this.agents = agents;
        this.queries = queries;
        this.metrics = metrics;
        this.cardAnswers = cardAnswers;
        this.actions = actions;
        this.broadcasts = broadcasts;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance panel(@QueryParam("msg") String msg) {
        return Templates.panel(queries.dashboard(), msg == null ? null : MESSAGES.get(msg), msg != null && ERRORS.contains(msg));
    }

    /** Design doc §12: the numbers behind any decision to enable auto-send for a slot type. */
    @GET
    @Path("metrics")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance metrics() {
        return Templates.metrics(metrics.metrics());
    }

    /** Design doc §8.2 and §10: "What the agent knows", the facts it reuses without asking. */
    @GET
    @Path("memory")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance memory(@QueryParam("msg") String msg) {
        return Templates.memory(insights.memory(), msg == null ? null : MESSAGES.get(msg));
    }

    /** Brief §10: the specialized agents, what routes an email to each, their prompt and their own context. */
    @GET
    @Path("agents")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance agents() {
        return Templates.agents(agents.page());
    }

    /** What the agents did recently, from the audit log, and what went to the mailbox. */
    @GET
    @Path("activity")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance activity() {
        return Templates.activity(insights.activity());
    }

    @POST
    @Path("memory/facts/{id}/forget")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response forget(@PathParam("id") long id) {
        String code = actions.forgetFact(id) ? "forgot" : "nothing";
        return Response.seeOther(URI.create("/panel/memory?msg=" + code)).build();
    }

    @POST
    @Path("cards/{id}/answer")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response answer(@PathParam("id") long id, @RestForm long version, @RestForm String value,
            @RestForm String scope) {
        ScopeChoice choice = "CARD_AUDIENCE".equals(scope) ? ScopeChoice.CARD_AUDIENCE : ScopeChoice.THESE_EMAILS;
        try {
            cardAnswers.answer(id, version, value, choice);
            return back("answered");
        } catch (IllegalStateException e) {
            return back("card_changed");
        } catch (IllegalArgumentException e) {
            return back("bad_value");
        }
    }

    @POST
    @Path("cards/{id}/approve-all")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response approveAll(@PathParam("id") long id) {
        // the outliers are read again here, never taken from the form
        int sent = actions.approveAll(id, queries.outliersOf(id));
        Log.infof("owner approved %d replies of card %d", sent, id);
        return back(sent > 0 ? "sent_all" : "nothing");
    }

    @POST
    @Path("drafts/{emailId}/send")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response send(@PathParam("emailId") long emailId) {
        return back(actions.sendDraft(emailId) ? "sent" : "nothing");
    }

    @POST
    @Path("drafts/{emailId}/reject")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reject(@PathParam("emailId") long emailId) {
        return back(actions.rejectDraft(emailId) ? "rejected" : "nothing");
    }

    /** The owner edits a saved draft and sends their version (design doc §7.6). */
    @POST
    @Path("drafts/{emailId}/edit-send")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response editAndSend(@PathParam("emailId") long emailId, @RestForm String body) {
        return ownerReply(emailId, body);
    }

    /** The owner answers an escalated email themselves, from scratch or from the stopped draft (design doc §6). */
    @POST
    @Path("escalations/{emailId}/reply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response replyToEscalation(@PathParam("emailId") long emailId, @RestForm String body) {
        return ownerReply(emailId, body);
    }

    /** ESCALATED → READY: the writer tries again, only when every answer it needs is there. */
    @POST
    @Path("escalations/{emailId}/retry")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response retry(@PathParam("emailId") long emailId) {
        return back(actions.retryWriter(emailId) ? "retried" : "nothing");
    }

    private Response ownerReply(long emailId, String body) {
        try {
            return back(actions.sendOwnerReply(emailId, body) ? "sent_edited" : "nothing");
        } catch (IllegalArgumentException e) {
            return back("bad_reply");
        }
    }

    @POST
    @Path("escalations/{emailId}/close")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response close(@PathParam("emailId") long emailId) {
        return back(actions.closeEscalation(emailId) ? "closed" : "nothing");
    }

    @POST
    @Path("broadcasts/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response approveBroadcast(@PathParam("id") long id) {
        return back(broadcasts.approve(id) ? "broadcast" : "nothing");
    }

    /** Ends the session: the login cookie is expired (form login keeps no server-side session to destroy). */
    @POST
    @Path("logout")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logout() {
        NewCookie expired = new NewCookie.Builder("quarkus-credential").value("").path("/").maxAge(0).httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT).build();
        return Response.seeOther(URI.create("/login")).cookie(expired).build();
    }

    /** Post/redirect/get: a reload repeats the GET, never the action. */
    private static Response back(String code) {
        return Response.seeOther(URI.create("/panel?msg=" + code)).build();
    }
}
