package stream.api.adapter.log;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class LogViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogViewApplication.class, args);
    }

}
