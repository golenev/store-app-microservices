package stageTests;

import com.shop.store.StoreServiceApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

/** Verifies the real bootstrap SQL, database ownership and Flyway lifecycle without a Kafka consumer. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(classes = StoreServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"store.listener.enabled=false", "spring.kafka.admin.auto-create=false"})
class StoreRuntimeTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bootstrap_db").withUsername("bootstrap_admin").withPassword("bootstrap_local")
            .withInitScript("bootstrap/01-databases.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TestRestTemplate http;

    /** Points STORE to its restricted role on the actual three-database bootstrap container. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl().replace("bootstrap_db", "store_db"));
        registry.add("spring.datasource.username", () -> "store_app");
        registry.add("spring.datasource.password", () -> "store_local");
    }

    /** V1/V2 preserve legacy data and create new scopes once; repeat migration neither rewrites data nor fixtures. */
    @Test
    void migrationIsRepeatableWithoutDestroyingData() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("store_app");
        jdbc.update("insert into orders(id,created_at,order_sum,items) values(42,now(),1.00,'[]'::jsonb)");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders where id=42", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT store_id FROM store_scopes ORDER BY store_id", String.class)).containsExactly("S-1", "S-2");
    }

    /** Each role connects only to its own database; other service and maintenance databases reject it. */
    @Test
    void applicationRolesCannotConnectToOtherServiceDatabases() throws SQLException {
        for (String service : new String[]{"store", "tariffs", "warehouse"}) {
            String ownUrl = postgres.getJdbcUrl().replace("bootstrap_db", service + "_db");
            try (var connection = DriverManager.getConnection(ownUrl, service + "_app", service + "_local")) {
                assertThat(connection.isValid(2)).isTrue();
            }
            for (String other : new String[]{"store_db", "tariffs_db", "warehouse_db", "bootstrap_db", "postgres", "template1"}) {
                if (!other.equals(service + "_db")) {
                    String otherUrl = postgres.getJdbcUrl().replace("bootstrap_db", other);
                    assertThatThrownBy(() -> DriverManager.getConnection(otherUrl, service + "_app", service + "_local"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("permission denied for database");
                }
            }
        }
    }

    /** A started STORE reports public HTTP health without requiring the transitional Basic credentials. */
    @Test
    void healthIsPublicAndDatabaseIsUp() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
