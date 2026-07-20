package com.instalego.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Hibernate's ddl-auto=update does not widen CHECK constraints it auto-generates for
 * {@code @Enumerated(EnumType.STRING)} columns when new enum constants are added later — it only
 * adds genuinely new columns/tables. That leaves already-deployed databases with a stale
 * constraint listing only the OLD enum values, which then rejects rows using a newly added value
 * (e.g. "NEEDS_MORE_DOCUMENTS" being rejected by verification_jobs_status_check).
 *
 * Rather than requiring a manual psql session against production every time an enum grows, drop
 * those auto-generated constraints once on every startup — after that the status column just
 * behaves like a plain varchar. Safe to run repeatedly (IF EXISTS) and works on both H2 (local
 * dev) and PostgreSQL (prod).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaFixRunner implements ApplicationRunner {

    private final DataSource dataSource;

    private static final String[] FIXES = {
            "ALTER TABLE verification_jobs DROP CONSTRAINT IF EXISTS verification_jobs_status_check"
    };

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : FIXES) {
                try {
                    stmt.execute(sql);
                    log.debug("Startup schema fix applied: {}", sql);
                } catch (Exception e) {
                    log.debug("Startup schema fix skipped ({}): {}", sql, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not run startup schema fixes: {}", e.getMessage());
        }
    }
}
