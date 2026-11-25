package au_project_work.parking_app.service;

import au_project_work.parking_app.api.ForecastCache;
import au_project_work.parking_app.api.Dtos.AddressDto;
import au_project_work.parking_app.api.Dtos.AdminLotDto;
import au_project_work.parking_app.api.Dtos.AdminRowDto;
import au_project_work.parking_app.api.Dtos.AdminSpotDto;
import au_project_work.parking_app.api.Dtos.CreateLotRequest;
import au_project_work.parking_app.api.Dtos.ParkedSensorDto;
import au_project_work.parking_app.model.Address;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import au_project_work.parking_app.repository.ParkingLotRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lot, row, spot and sensor changes for the admin API, one transaction per method. Plain SQL on purpose: the
 * entities hide archived rows (@SQLRestriction), which is wrong for a sensor that is moved off an archived spot.
 * Unique-key races surface as DuplicateKeyException; the controller turns them into 409.
 */
@Service
public class AdminService {

    // a spot shows in the app only while it and its row and lot are all unarchived
    private static final String SPOT_JOINS = """
            FROM parking_spots sp
            JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id
            JOIN parking_lots l ON l.parking_lot_id = r.parking_lot_id
            """;
    private static final String ACTIVE = "sp.archived_at IS NULL AND r.archived_at IS NULL AND l.archived_at IS NULL";

    private final JdbcTemplate jdbc;
    private final ParkingLotRepository parkingLotRepository;
    private final ForecastCache forecastCache;
    private final EntranceMeasurementRepository entranceMeasurementRepository;

    public AdminService(JdbcTemplate jdbc, ParkingLotRepository parkingLotRepository, ForecastCache forecastCache,
            EntranceMeasurementRepository entranceMeasurementRepository) {
        this.entranceMeasurementRepository = entranceMeasurementRepository;
        this.forecastCache = forecastCache;
        this.jdbc = jdbc;
        this.parkingLotRepository = parkingLotRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminLotDto> lots() {
        Map<Integer, Integer> sensorOfSpot = new HashMap<>();
        jdbc.query("SELECT parking_spot_id, parking_spot_sensor_id FROM parking_spot_sensors",
                rs -> {
                    sensorOfSpot.put(rs.getInt(1), rs.getInt(2));
                });
        Map<Integer, List<Integer>> entranceOfLot = new HashMap<>();
        jdbc.query("SELECT parking_lot_id, entrance_sensor_id FROM entrance_sensors ORDER BY entrance_sensor_id",
                rs -> {
                    entranceOfLot.computeIfAbsent(rs.getInt(1), k -> new java.util.ArrayList<>()).add(rs.getInt(2));
                });
        return parkingLotRepository.findAll(Sort.by("id")).stream().map(lot -> {
            Address a = lot.getAddress();
            List<AdminRowDto> rows = lot.getRows().stream()
                    .map(row -> new AdminRowDto(row.getId(), row.getSpots().stream()
                            .map(s -> new AdminSpotDto(s.getId(), s.getOccupied(), sensorOfSpot.get(s.getId())))
                            .toList()))
                    .toList();
            return new AdminLotDto(lot.getId(),
                    new AddressDto(a.getStreet(), a.getHouseNr(), a.getCity(), a.getLatitude(), a.getLongitude()),
                    rows, entranceOfLot.getOrDefault(lot.getId(), List.of()),
                    entranceMeasurementRepository.carsInside(lot.getId())); // same count as the app (ParkingLotController)
        }).toList();
    }

    @Transactional
    public int createLot(CreateLotRequest r) {
        Integer addressId = jdbc.queryForObject(
                "INSERT INTO addresses (city, street, house_nr, latitude, longitude) VALUES (?, ?, ?, ?, ?) RETURNING address_id",
                Integer.class, r.city(), r.street(), r.houseNr(), r.latitude(), r.longitude());
        return jdbc.queryForObject("INSERT INTO parking_lots (address_id) VALUES (?) RETURNING parking_lot_id",
                Integer.class, addressId);
    }

    @Transactional
    public int addRow(int lotId) {
        requireLot(lotId);
        return jdbc.queryForObject("INSERT INTO parking_rows (parking_lot_id) VALUES (?) RETURNING parking_row_id",
                Integer.class, lotId);
    }

    /** A sensor still attached to an archived spot is moved here instead of created again. */
    @Transactional
    public int addSpot(int rowId, int sensorId) {
        if (!exists("SELECT 1 FROM parking_rows r JOIN parking_lots l ON l.parking_lot_id = r.parking_lot_id "
                + "WHERE r.parking_row_id = ? AND r.archived_at IS NULL AND l.archived_at IS NULL FOR SHARE", rowId)) {
            throw notFound();
        }
        // serialises two calls for the same sensor: the second waits, then sees the first one's spot (409)
        jdbc.queryForList("SELECT 1 FROM parking_spot_sensors WHERE parking_spot_sensor_id = ? FOR UPDATE", sensorId);
        List<Boolean> inUse = jdbc.queryForList("SELECT (" + ACTIVE + ") " + SPOT_JOINS.replace("FROM parking_spots sp",
                "FROM parking_spot_sensors s JOIN parking_spots sp ON sp.parking_spot_id = s.parking_spot_id")
                + " WHERE s.parking_spot_sensor_id = ?", Boolean.class, sensorId);
        if (!inUse.isEmpty() && inUse.get(0)) {
            throw conflict();
        }
        forecastCache.clear();
        int spotId = jdbc.queryForObject("INSERT INTO parking_spots (parking_row_id) VALUES (?) RETURNING parking_spot_id",
                Integer.class, rowId);
        if (inUse.isEmpty()) {
            jdbc.update("INSERT INTO parking_spot_sensors (parking_spot_sensor_id, parking_spot_id) VALUES (?, ?)",
                    sensorId, spotId);
        } else {
            jdbc.update("UPDATE parking_spot_sensors SET parking_spot_id = ?, attached_at = now() WHERE parking_spot_sensor_id = ?",
                    spotId, sensorId);
            freshSpot(spotId);
        }
        return spotId;
    }

    @Transactional
    public void addEntranceSensor(int lotId, int sensorId) {
        requireLot(lotId);
        jdbc.update("INSERT INTO entrance_sensors (entrance_sensor_id, parking_lot_id) VALUES (?, ?)", sensorId, lotId);
    }

    @Transactional
    public void moveSensor(int sensorId, int spotId) {
        List<Integer> current = jdbc.queryForList(
                "SELECT parking_spot_id FROM parking_spot_sensors WHERE parking_spot_sensor_id = ? FOR UPDATE",
                Integer.class, sensorId);
        if (current.isEmpty() || !exists("SELECT 1 " + SPOT_JOINS + " WHERE sp.parking_spot_id = ? AND " + ACTIVE
                + " FOR SHARE", spotId)) {
            throw notFound();
        }
        if (current.get(0) == spotId) {
            return; // already there
        }
        // a sensor that serves a live spot is not taken away: archive that spot first
        if (exists("SELECT 1 " + SPOT_JOINS + " WHERE sp.parking_spot_id = ? AND " + ACTIVE, current.get(0))
                || exists("SELECT 1 FROM parking_spot_sensors WHERE parking_spot_id = ?", spotId)) {
            throw conflict();
        }
        jdbc.update("UPDATE parking_spot_sensors SET parking_spot_id = ?, attached_at = now() WHERE parking_spot_sensor_id = ?", spotId, sensorId);
        freshSpot(spotId);
    }

    // the sensor's old reading says nothing about the new spot: show it free until the board reports
    private void freshSpot(int spotId) {
        forecastCache.clear(); // a moved sensor changes which history the lot's forecast uses
        jdbc.update("UPDATE parking_spots SET occupied = false, updated_at = now() WHERE parking_spot_id = ?", spotId);
    }

    /** Sensors still attached to an archived spot (or one in an archived row or lot): free to move. */
    @Transactional(readOnly = true)
    public List<ParkedSensorDto> parkedSensors() {
        return jdbc.query("SELECT s.parking_spot_sensor_id, s.parking_spot_id FROM parking_spot_sensors s "
                + "JOIN parking_spots sp ON sp.parking_spot_id = s.parking_spot_id "
                + "JOIN parking_rows r ON r.parking_row_id = sp.parking_row_id "
                + "JOIN parking_lots l ON l.parking_lot_id = r.parking_lot_id "
                + "WHERE sp.archived_at IS NOT NULL OR r.archived_at IS NOT NULL OR l.archived_at IS NOT NULL "
                + "ORDER BY s.parking_spot_sensor_id", (rs, i) -> new ParkedSensorDto(rs.getInt(1), rs.getInt(2)));
    }

    // archiving never touches the children or a spot's sensor: the sensor stays attached to the archived spot,
    // still records readings, and can be moved to a new spot later
    @Transactional
    public void archiveLot(int id) {
        archive("parking_lots", "parking_lot_id", id);
    }

    @Transactional
    public void archiveRow(int id) {
        archive("parking_rows", "parking_row_id", id);
    }

    @Transactional
    public void archiveSpot(int id) {
        archive("parking_spots", "parking_spot_id", id);
    }

    // table and column are constants of this class, never request input
    private void archive(String table, String idColumn, int id) {
        forecastCache.clear(); // the forecast counts the lot's live spots
        if (jdbc.update("UPDATE " + table + " SET archived_at = now() WHERE " + idColumn + " = ? AND archived_at IS NULL", id) == 0) {
            throw notFound();
        }
    }

    private void requireLot(int lotId) {
        if (!exists("SELECT 1 FROM parking_lots WHERE parking_lot_id = ? AND archived_at IS NULL FOR SHARE", lotId)) {
            throw notFound();
        }
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (" + sql + ")", Boolean.class, args));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Already in use");
    }
}
