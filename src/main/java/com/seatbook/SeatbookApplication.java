package com.seatbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatbookApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatbookApplication.class, args);
    }
}
