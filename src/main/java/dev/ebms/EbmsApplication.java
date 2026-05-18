package dev.ebms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EbmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EbmsApplication.class, args);
    }
}
