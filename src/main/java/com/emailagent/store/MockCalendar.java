package com.emailagent.store;

import com.emailagent.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * The owner's calendar, tagged events only. A match only proposes a value: the owner confirms it in one click
 * (design doc §4.2), because an event title is not an answer the owner has written.
 */
@Singleton
public class MockCalendar extends KnowledgeSource {

    public MockCalendar(ObjectMapper mapper, AppConfig config) {
        super(mapper, config.calendarFixture());
    }
}
