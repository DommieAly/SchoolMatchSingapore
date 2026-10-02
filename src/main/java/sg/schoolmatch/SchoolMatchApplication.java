package sg.schoolmatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SchoolMatch SG — Spring Boot entry point. Settings: application.yml; {@code app.*} → config.AppProperties. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchoolMatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolMatchApplication.class, args);
    }
}
