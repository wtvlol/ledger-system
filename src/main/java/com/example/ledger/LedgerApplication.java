package com.example.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the local financial ledger application.
 */
@SpringBootApplication
public class LedgerApplication {
    /**
     * Starts the ledger HTTP application with the supplied startup arguments.
     *
     * @param args Spring Boot command-line configuration arguments.
     */
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
