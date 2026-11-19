package au_project_work.parking_app.mqtt;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.support.GenericMessage;

import au_project_work.parking_app.service.ParkingSpotMeasurementService;

class MeasurementListenerRetryTests {

    @Test
    void readingIsKeptThroughTwoDatabaseFailures() {
        ParkingSpotMeasurementService service = mock(ParkingSpotMeasurementService.class);
        doThrow(new DataAccessResourceFailureException("down")).doThrow(new DataAccessResourceFailureException("down"))
                .doNothing().when(service).record(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
        new SpotMeasurementListener(service).spotMessageHandler()
                .handleMessage(new GenericMessage<>("{\"device_id\": 3, \"occupied\": true}"));
        verify(service, times(3)).record(3, true); // 2 failures + the successful third try
    }

    @Test
    void badPayloadIsDroppedWithoutRetry() {
        ParkingSpotMeasurementService service = mock(ParkingSpotMeasurementService.class);
        var handler = new SpotMeasurementListener(service).spotMessageHandler();
        handler.handleMessage(new GenericMessage<>("not json"));
        handler.handleMessage(new GenericMessage<>("{\"device_id\": 3}"));
        verify(service, never()).record(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
