package cl.duoc.cloudnative.inventory.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventStore {
    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean markIfNew(UUID eventId) {
        return jdbcTemplate.update(
                "INSERT INTO inventory_processed_event(event_id, processed_at) "
                        + "VALUES (?, CURRENT_TIMESTAMP) ON CONFLICT (event_id) DO NOTHING",
                eventId) == 1;
    }
}
