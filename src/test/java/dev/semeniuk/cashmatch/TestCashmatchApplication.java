package dev.semeniuk.cashmatch;

import org.springframework.boot.SpringApplication;

/** `./mvnw spring-boot:test-run` starts the app against a throwaway Postgres container. */
public class TestCashmatchApplication {
    public static void main(String[] args) {
        SpringApplication.from(CashmatchApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
