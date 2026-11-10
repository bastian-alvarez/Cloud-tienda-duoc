package cl.duoc.cloudnative.payment.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProcessedEventStoreTest {
    @Test
    void insertsProcessingTimestampWhenMarkingEvent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID eventId = UUID.randomUUID();
        String sql = "INSERT INTO payment_processed_event(event_id, processed_at) "
                + "VALUES (?, CURRENT_TIMESTAMP) ON CONFLICT (event_id) DO NOTHING";
        when(jdbcTemplate.update(sql, eventId)).thenReturn(1);

        assertTrue(new ProcessedEventStore(jdbcTemplate).markIfNew(eventId));
        verify(jdbcTemplate).update(sql, eventId);
    }
}
