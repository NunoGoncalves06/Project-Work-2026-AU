package au_project_work.parking_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.random.RandomGenerator;

// scheduling drives ParkingUpdates, which watches for changes and pushes them to the apps
@SpringBootApplication
@EnableScheduling
public class ParkingAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(ParkingAppApplication.class, args);
	}

	// injectable so the traffic simulator can be tested with a fixed clock and seed
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	RandomGenerator randomGenerator() {
		// java.util.Random lives in java.base; getDefault() needs jdk.random, missing from the slim runtime image
		return new java.util.Random();
	}
}
