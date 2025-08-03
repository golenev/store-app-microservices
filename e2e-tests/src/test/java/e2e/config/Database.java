package e2e.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class Database {
    private static final JdbcTemplate TEMPLATE = new JdbcTemplate(
            new DriverManagerDataSource(
                    "jdbc:postgresql://localhost:34567/mydatabase",
                    "myuser",
                    "mypassword"));

    private Database() {
    }

    public static JdbcTemplate template() {
        return TEMPLATE;
    }
}
