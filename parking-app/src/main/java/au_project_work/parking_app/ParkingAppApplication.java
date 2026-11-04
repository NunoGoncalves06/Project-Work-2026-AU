package au_project_work.parking_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// scheduling drives ParkingUpdates, which watches for changes and pushes them to the apps
@SpringBootApplication
@EnableScheduling
public class ParkingAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(ParkingAppApplication.class, args);
	}
}
