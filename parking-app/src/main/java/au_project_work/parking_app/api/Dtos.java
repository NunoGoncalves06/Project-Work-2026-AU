package au_project_work.parking_app.api;

import java.time.Instant;
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

    // free spots usually seen at this time of the week; see ParkingLotRepository.forecast
    public record ForecastDto(Instant at, int freeSpots) {
    }

    // what the dashboard needs to start the Cognito login; mode is cognito, off or unconfigured
    public record AuthConfigDto(String mode, String region, String userPoolId, String clientId, String domain) {
    }

    // role is the highest of viewer, operator, admin
    public record MeDto(String sub, String username, String role) {
    }

    public record LotDetailDto(Integer id, AddressDto address, int totalSpots, long freeSpots, long carsInside,
                               List<RowDto> rows, List<ForecastDto> forecast) {
    }
}
