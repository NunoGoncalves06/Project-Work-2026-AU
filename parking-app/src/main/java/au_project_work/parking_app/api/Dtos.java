package au_project_work.parking_app.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class Dtos {

    private static final String NO_CONTROL = "^[^\\p{Cntrl}]*$";

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

    // admin view of a lot: every spot with its sensor id (null when it has none) and the lot's entrance sensors
    public record AdminSpotDto(Integer id, boolean occupied, Integer sensorId) {
    }

    public record AdminRowDto(Integer id, List<AdminSpotDto> spots) {
    }

    public record AdminLotDto(Integer id, AddressDto address, List<AdminRowDto> rows, List<Integer> entranceSensorIds) {
    }

    public record IdDto(Integer id) {
    }

    // a spot sensor whose spot, row or lot is archived; it can be moved to a new spot
    public record ParkedSensorDto(Integer sensorId, Integer spotId) {
    }

    // admin requests: bounded; a body with unknown fields is refused (spring.jackson fail-on-unknown-properties)
    public record CreateLotRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = NO_CONTROL) String street,
            @NotBlank @Size(max = 10) @Pattern(regexp = NO_CONTROL) String houseNr,
            @NotBlank @Size(max = 50) @Pattern(regexp = NO_CONTROL) String city,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude) {
    }

    // sensorId is the device_id the board sends
    public record SensorRequest(@NotNull @Min(1) Integer sensorId) {
    }

    public record MoveSensorRequest(@NotNull @Min(1) Integer spotId) {
    }

    public record SpotReadingRequest(@NotNull Boolean occupied) {
    }

    public record StartTrafficRequest(@NotNull @Min(1) Integer lotId,
                                      @NotNull @DecimalMin("0") @DecimalMax("1") Double targetOccupancy,
                                      @NotNull @Min(1) @Max(60) Integer changesPerMinute) {
    }

    // lotId, targetOccupancy, changesPerMinute, startedAt and endsAt are null when nothing runs
    public record TrafficStatusDto(boolean running, Integer lotId, Double targetOccupancy, Integer changesPerMinute,
                                   Instant startedAt, Instant endsAt, int changes) {
    }
}
