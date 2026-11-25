package au_project_work.parking_app.api;

import au_project_work.parking_app.model.Address;
import au_project_work.parking_app.model.ParkingLot;
import au_project_work.parking_app.repository.EntranceMeasurementRepository;
import au_project_work.parking_app.repository.ParkingLotRepository;
import au_project_work.parking_app.api.Dtos.AddressDto;
import au_project_work.parking_app.api.Dtos.ForecastDto;
import au_project_work.parking_app.api.Dtos.LotDetailDto;
import au_project_work.parking_app.api.Dtos.LotSummaryDto;
import au_project_work.parking_app.api.Dtos.RowDto;
import au_project_work.parking_app.api.Dtos.SpotDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping(path = "/parking-lots")
public class ParkingLotController {

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Autowired
    private EntranceMeasurementRepository entranceMeasurementRepository;

    @Autowired
    private ForecastCache forecastCache;

    @GetMapping
    @Transactional(readOnly = true)
    public List<LotSummaryDto> getAll() {
        return parkingLotRepository.findAll(Sort.by("id")).stream()
                .map(lot -> toDetail(lot, List.of()))
                .map(d -> new LotSummaryDto(d.id(), d.address(), d.totalSpots(), d.freeSpots(), d.carsInside(), d.countedBy()))
                .toList();
    }

    @GetMapping(path = "/{id}")
    @Transactional(readOnly = true)
    public LotDetailDto getOne(@PathVariable Integer id) {
        return parkingLotRepository.findById(id)
                .map(lot -> toDetail(lot, "entrance".equals(lot.getCountedBy()) ? List.<ForecastDto>of() : forecastCache.get(lot.getId(), () -> parkingLotRepository.forecast(lot.getId()).stream()
                        .map(f -> new ForecastDto(f.getAt(), f.getFreeSpots())).toList())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown parking lot " + id));
    }

    // shortcut: loads every spot of every lot (a query per row); fine for a handful of lots, switch to an aggregate query if lots grow to hundreds
    private LotDetailDto toDetail(ParkingLot lot, List<ForecastDto> forecast) {
        List<RowDto> rows = lot.getRows().stream().map(row -> {
            List<SpotDto> spots = row.getSpots().stream()
                    .map(s -> new SpotDto(s.getId(), s.getOccupied()))
                    .toList();
            return new RowDto(row.getId(), spots.stream().filter(s -> !s.occupied()).count(), spots);
        }).toList();
        int totalSpots = rows.stream().mapToInt(r -> r.spots().size()).sum();
        boolean gate = "entrance".equals(lot.getCountedBy());
        long carsInside = entranceMeasurementRepository.carsInside(lot.getId());
        // gate-counted: the spot states mean nothing, free = capacity minus the cars the gate counted
        long freeSpots = gate ? Math.max(0, totalSpots - carsInside) : rows.stream().mapToLong(RowDto::freeSpots).sum();
        Address a = lot.getAddress();
        // after demo testing: switch carsInside to carsInsideResyncedAt4am (daily resync to the spot sensors)
        return new LotDetailDto(lot.getId(), new AddressDto(a.getStreet(), a.getHouseNr(), a.getCity(), a.getLatitude(), a.getLongitude()),
                totalSpots, freeSpots, carsInside, lot.getCountedBy(), rows, forecast);
    }
}
