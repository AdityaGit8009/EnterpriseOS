package com.enterpriseos;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * Explicit external test database, or a disposable Docker MySQL; never an
 * ordinary app DB_URL.
 */
public final class TestDatabase {
    private static MySQLContainer<?> container;

    private TestDatabase() {
    }

    public static boolean available() {
        if (!System.getenv().getOrDefault("TEST_DB_URL", "").isBlank())
            return true;
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception ex) {
            return false;
        }
    }

    public static synchronized void configure(DynamicPropertyRegistry registry) {
        String external = System.getenv().getOrDefault("TEST_DB_URL", "");
        if (!external.isBlank()) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "root"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        } else {
            if (container == null) {
                container = new MySQLContainer<>("mysql:8.4");
                container.start();
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }
}
