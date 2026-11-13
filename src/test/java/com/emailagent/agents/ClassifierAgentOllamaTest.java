package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmException;
import com.emailagent.mailbox.MailboxImporter;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real model on the demo inbox. Checks the labels that matter for routing, prints the rest. */
@QuarkusTest
@Tag("ollama")
class ClassifierAgentOllamaTest {

    @Inject MailboxImporter importer;
    @Inject ClassifierAgent classifier;

    @Test
    void realModelExtractsTheExpectedSlots() throws LlmException {
        importer.importNew();
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : new String[] {"m01", "m02", "m03", "m09", "m10", "m12"}) {
            EmailSnapshot email = QuarkusTransaction.requiringNew().call(() -> EmailSnapshot.of(
                    EmailEntity.findByMessageId("<" + id + "@student.univ.example>").orElseThrow()));
            StepResult result = classifier.run(email);
            out.put(id, result.next() + " " + result.output());
            System.out.println(id + " -> " + out.get(id));
        }

        assertThat(out.get("m01")).contains("GRADES_PUBLICATION_DATE").contains("\"language\":\"FR\"");
        assertThat(out.get("m02")).contains("GRADES_PUBLICATION_DATE").contains("\"language\":\"EN\"");
        assertThat(out.get("m03")).contains("EXAM_ROOM");
        assertThat(out.get("m09")).containsAnyOf("INDIVIDUAL_RESULT", "\"sensitivity\":\"PERSONAL\"");
        assertThat(out.get("m12")).containsAnyOf("MEETING_AVAILABILITY", "\"intent\":\"MEETING\"");
        // m10 is escalated by the router's keyword rule whatever the model says; printed for the report
    }
}
