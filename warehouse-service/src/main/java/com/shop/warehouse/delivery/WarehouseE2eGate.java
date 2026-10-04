package com.shop.warehouse.delivery;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/** Persisted fault gates for the explicit e2e profile only; exposes no HTTP control and never exists in ordinary deployments. */
@Component
@Profile("e2e")
@DependsOnDatabaseInitialization
public class WarehouseE2eGate {
    private final JdbcTemplate jdbc;

    /** Creates only a test-profile control table after Flyway; gates survive application restart but do not alter business migrations. */
    public WarehouseE2eGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS e2e_gates (store_id VARCHAR(64) NOT NULL, subject_id VARCHAR(64) NOT NULL, point VARCHAR(32) NOT NULL, blocked BOOLEAN NOT NULL DEFAULT TRUE, reached_subject VARCHAR(64), hits BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(store_id,subject_id,point))");
    }

    /** Atomically acknowledges a reached boundary and skips work when blocked; a claimed lease remains persisted for crash/reclaim tests. */
    public boolean permits(String storeId, String subjectId, String point) {
        return jdbc.queryForList("UPDATE e2e_gates SET reached_subject=?, hits=hits+1 WHERE store_id=? AND (subject_id=? OR subject_id='*') AND point=? AND blocked=TRUE RETURNING point", subjectId, storeId, subjectId, point).isEmpty();
    }
}
