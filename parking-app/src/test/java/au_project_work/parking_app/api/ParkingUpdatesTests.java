package au_project_work.parking_app.api;

import au_project_work.parking_app.repository.ParkingLotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
}
