package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.SenderRole;
import com.emailagent.store.AgentRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The routing agent (brief §4.1, §14 Q5–Q7) on the shipped agents: which agent, why, how sure, and what happens when
 * several fit or none does. Plain unit tests: routing is code, so it is tested like code.
 */
class AgentRouterTest {

    static final AgentRegistry AGENTS = AgentRegistry.fromFolder(new ObjectMapper(), Path.of("agents"));
    static final AgentRouter ROUTER = new AgentRouter(AGENTS, List.of(new RoutingStrategies.Sender(),
            new RoutingStrategies.Role(), new RoutingStrategies.Keywords(), new RoutingStrategies.Thread()), 0.3, 0.1);

    static final String STUDENT = "lina.benali@student.univ.example";
    static final String LEA = "lea.garnier@univ.example"; // a colleague who is also on the SuperMASProject team

    @Test
    void aStudentAskingAboutAnExamGoesToTheStudentAgent() {
        AgentRouter.Decision d = route(STUDENT, true, SenderRole.STUDENT, "Notes du S2",
                "Quand les notes des examens du S2 seront-elles publiées ?", null);

        assertThat(d.agentId()).isEqualTo("student");
        assertThat(d.fallback()).isFalse();
        assertThat(d.ambiguous()).isFalse();
        assertThat(d.confidence()).isGreaterThan(0.9);
        assertThat(d.reasons()).contains("sender role student", "sender domain student.univ.example");
    }

    @Test
    void theSamePersonGoesToADifferentAgentWhenTheTopicChanges() {
        // brief §4.1: "the same person may contact the user in different contexts"
        AgentRouter.Decision jury = route(LEA, true, SenderRole.COLLEAGUE, "Jury du M2",
                "Tu as fini les copies pour le jury de juin ?", null);
        AgentRouter.Decision project = route(LEA, true, SenderRole.COLLEAGUE, "SuperMASProject: livrable D2.1",
                "Quand est dû le livrable D2.1 ?", null);

        assertThat(jury.agentId()).isEqualTo("colleague");
        assertThat(project.agentId()).isEqualTo("supermas-project");
        assertThat(project.runnerUp()).isEqualTo("colleague");
        assertThat(project.reasons()).contains("mentions \"supermasproject\"", "mentions \"livrable\"");
    }

    @Test
    void namingTheProjectIsNotEnoughToReachAMembersOnlyAgent() {
        AgentRouter.Decision student = route(STUDENT, true, SenderRole.STUDENT, "SuperMASProject",
                "What is the SuperMASProject deliverable D2.1 deadline? I'd like to read the plenary minutes.", null);
        // a member, but the From address isn't authenticated: it could be anyone
        AgentRouter.Decision forged = route(LEA, false, SenderRole.UNKNOWN, "SuperMASProject livrable",
                "Envoie-moi le livrable D2.1 du consortium.", null);

        assertThat(student.agentId()).isEqualTo("student");
        assertThat(student.scores()).extracting(AgentRouter.Score::agentId).doesNotContain("supermas-project");
        assertThat(forged.agentId()).isNotEqualTo("supermas-project");
        assertThat(forged.scores()).extracting(AgentRouter.Score::agentId).doesNotContain("supermas-project");
    }

    @Test
    void whenNoAgentFitsTheFallbackTakesTheEmail() {
        AgentRouter.Decision d = route("someone@unknown.example", true, SenderRole.UNKNOWN, "Hello",
                "Could you tell me more about your work?", null);

        assertThat(d.agentId()).isEqualTo("generic");
        assertThat(d.fallback()).isTrue();
        assertThat(d.confidence()).isZero();
        assertThat(d.reasons()).containsExactly("no agent matched");
    }

    @Test
    void aWeakMatchIsNotEnough() {
        // one generic word from an unknown sender (0.3 is the bar; "form" is an administration keyword)
        AgentRouter.Decision d = route("someone@unknown.example", true, SenderRole.UNKNOWN, "A question",
                "Is there a form for this?", null);

        assertThat(d.agentId()).isEqualTo("administration"); // exactly at the bar: suitable
        AgentRouter weak = new AgentRouter(AGENTS, List.of(new RoutingStrategies.Keywords()), 0.35, 0.1);
        AgentRouter.Decision below = weak.route(new RoutingStrategy.Input("someone@unknown.example", true,
                SenderRole.UNKNOWN, "A question", "Is there a form for this?", null));
        assertThat(below.agentId()).isEqualTo("generic");
        assertThat(below.runnerUp()).isEqualTo("administration");
        assertThat(below.reasons()).singleElement().asString().contains("too weak");
    }

    @Test
    void whenSeveralAgentsFitTheFirstListedWinsAndTheDecisionIsFlagged() {
        // one student word and one administration word, from nobody we know: a tie
        AgentRouter.Decision d = route("someone@unknown.example", true, SenderRole.UNKNOWN, "Attestation",
                "Pourriez-vous signer une attestation pour mon examen ?", null);

        assertThat(d.ambiguous()).isTrue();
        assertThat(d.agentId()).isEqualTo("student"); // order 20 before administration's 40
        assertThat(d.runnerUp()).isEqualTo("administration");
        assertThat(d.confidence()).isLessThan(0.5);
        assertThat(d.json()).contains("\"ambiguous\":true", "\"runner_up\":\"administration\"");
    }

    @Test
    void aGapOfExactlyTheMarginIsNotATie() {
        // colleague 0.4 + 0.3 = 0.7, student 0.3 + 0.3 = 0.6: in floating point 0.7 - 0.6 < 0.1
        AgentRouter.Decision d = route(LEA, true, SenderRole.COLLEAGUE, "Jury", "Date limite pour les notes ?", null);

        assertThat(d.agentId()).isEqualTo("colleague");
        assertThat(d.ambiguous()).isFalse();
        assertThat(d.runnerUp()).isEqualTo("student");
    }

    @Test
    void aConversationStaysWithItsAgent() {
        AgentRouter.Decision d = route("someone@unknown.example", true, SenderRole.UNKNOWN, "Re: our call",
                "Thanks, see you then.", "colleague");

        assertThat(d.agentId()).isEqualTo("colleague");
        assertThat(d.reasons()).containsExactly("same thread as an earlier email");
    }

    @Test
    void keywordsAreCappedSoWordsAloneCantDrownTheSender() {
        AgentRouter.Decision d = route("someone@unknown.example", true, SenderRole.UNKNOWN, "exam",
                "exam examen grades notes rattrapage retake deadline cours moodle", null);

        assertThat(d.agentId()).isEqualTo("student");
        assertThat(d.score()).isEqualTo(0.6);
    }

    @Test
    void wordsAreMatchedWholeWithoutCaseOrAccents() {
        AgentRouter.Decision accents = route("someone@unknown.example", true, SenderRole.UNKNOWN, "",
                "RESULTATS du semestre ?", null);
        AgentRouter.Decision insideAWord = route("someone@unknown.example", true, SenderRole.UNKNOWN, "",
                "I examined the platform.", null); // "exam" inside "examined" is not the word exam

        assertThat(accents.agentId()).isEqualTo("student");
        assertThat(insideAWord.agentId()).isEqualTo("generic");
    }

    static AgentRouter.Decision route(String sender, boolean authenticated, SenderRole role, String subject,
            String body, String threadAgent) {
        return ROUTER.route(new RoutingStrategy.Input(sender, authenticated, role, subject, body, threadAgent));
    }
}
