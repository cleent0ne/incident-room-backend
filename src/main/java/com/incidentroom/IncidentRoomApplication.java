package com.incidentroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // used later for scheduled cleanup jobs (e.g. stale attempt pruning); harmless if unused today
public class IncidentRoomApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentRoomApplication.class, args);
    }
}
