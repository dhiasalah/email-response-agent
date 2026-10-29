package com.emailagent.store;

import com.emailagent.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

/** The owner's documents (syllabus, regulations, course pages): an answer found here fills the slot. */
@Singleton
public class KnowledgeBase extends KnowledgeSource {

    public KnowledgeBase(ObjectMapper mapper, AppConfig config) {
        super(mapper, config.knowledgeBaseFixture());
    }
}
