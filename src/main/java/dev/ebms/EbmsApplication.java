package dev.ebms;

import dev.ebms.adapter.in.msh.EbmsSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(EbmsSecurityProperties.class)
public class EbmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EbmsApplication.class, args);
    }
}
