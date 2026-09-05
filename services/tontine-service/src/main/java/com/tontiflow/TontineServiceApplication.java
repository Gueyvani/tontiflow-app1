package com.tontiflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TontineServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TontineServiceApplication.class, args);
    }

}
