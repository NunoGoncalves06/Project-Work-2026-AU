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
import org.springframework.transaction.CannotCreateTransactionException;

import au_project_work.parking_app.service.EntranceMeasurementService;
import au_project_work.parking_app.service.ParkingSpotMeasurementService;

class MeasurementListenerRetryTests {

    private static final String ENTRANCE_JSON = "{\"device_id\": 1, \"dir\": 1}";

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
    void readingIsKeptWhenPostgresIsDownAndNoTransactionCanStart() {
        // what @Transactional record() really throws while Postgres restarts: not a DataAccessException
        ParkingSpotMeasurementService service = mock(ParkingSpotMeasurementService.class);
        doThrow(new CannotCreateTransactionException("no connection")).doNothing()
                .when(service).record(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
        new SpotMeasurementListener(service).spotMessageHandler()
                .handleMessage(new GenericMessage<>("{\"device_id\": 4, \"occupied\": false}"));
        verify(service, times(2)).record(4, false);
    }

    @Test
    void entranceReadingIsKeptThroughADatabaseFailure() {
        EntranceMeasurementService service = mock(EntranceMeasurementService.class);
        doThrow(new CannotCreateTransactionException("no connection")).doNothing()
                .when(service).record(anyInt(), anyInt());
        new EntranceMeasurementListener(service).entranceMessageHandler()
                .handleMessage(new GenericMessage<>(ENTRANCE_JSON));
        verify(service, times(2)).record(anyInt(), anyInt());
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
