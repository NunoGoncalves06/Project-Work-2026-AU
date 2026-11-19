package au_project_work.parking_app.api;

import au_project_work.parking_app.repository.ParkingLotRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ParkingUpdatesTests {

    @Test
    void changedEventIsSentOnlyWhenTheFingerprintChanges() throws Exception {
        ParkingLotRepository repo = mock(ParkingLotRepository.class);
        when(repo.fingerprint()).thenReturn("a", "a", "b").thenThrow(new RuntimeException("db down"));
        ParkingUpdates updates = new ParkingUpdates(repo);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ParkingStreamController(updates)).build();
        MvcResult stream = mvc.perform(get("/parking-lots/stream")).andReturn(); // one connected app

        updates.checkForChanges(); // first look: just remembers "a"
        updates.checkForChanges(); // same
        assertFalse(stream.getResponse().getContentAsString().contains("changed"));
        updates.checkForChanges(); // "b"
        assertTrue(stream.getResponse().getContentAsString().contains("event:changed"));
        updates.checkForChanges(); // database error: must not throw, stream stays open
    }

    @Test
    void anOutageIsLoggedOnceAndTheRecoveryToo() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ParkingUpdates.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            ParkingLotRepository repo = mock(ParkingLotRepository.class);
            when(repo.fingerprint()).thenThrow(new RuntimeException("db down")).thenThrow(new RuntimeException("db down"))
                    .thenReturn("a");
            ParkingUpdates updates = new ParkingUpdates(repo);
            MockMvcBuilders.standaloneSetup(new ParkingStreamController(updates)).build()
                    .perform(get("/parking-lots/stream")); // the check only runs while an app listens
            updates.checkForChanges();
            updates.checkForChanges();
            assertEquals(1, logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count()); // not every 2 s
            updates.checkForChanges();
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("works again")));
        } finally {
            logger.detachAppender(logs);
        }
    }
}
