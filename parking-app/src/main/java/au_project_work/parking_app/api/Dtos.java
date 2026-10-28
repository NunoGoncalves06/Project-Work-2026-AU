package au_project_work.parking_app.api;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    // latitude/longitude are null when the lot has no coordinates yet
    public record AddressDto(String street, String houseNr, String city, Double latitude, Double longitude) {
    }

    public record SpotDto(Integer id, boolean occupied) {
    }

    public record RowDto(Integer id, long freeSpots, List<SpotDto> spots) {
    }

    public record LotSummaryDto(Integer id, AddressDto address, int totalSpots, long freeSpots, long carsInside) {
    }

    public record LotDetailDto(Integer id, AddressDto address, int totalSpots, long freeSpots, long carsInside,
                               List<RowDto> rows) {
    }

    public record EntranceMeasurementRequest(Integer entranceSensorId, Integer measuredValue) {
    }

    public record ParkingSpotMeasurementRequest(Integer parkingSpotSensorId, Boolean occupied) {
    }
}
