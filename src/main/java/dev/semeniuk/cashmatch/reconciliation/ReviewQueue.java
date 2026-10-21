package dev.semeniuk.cashmatch.reconciliation;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class ReviewQueue {

    private final JdbcClient jdbc;

    ReviewQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID open(UUID transactionId, String reason, Optional<Investigation> investigation) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO review_tasks (id, transaction_id, reason, proposed_invoices,
                            agent_confidence, agent_reasoning, status)
                        VALUES (:id, :tx, :reason, :invoices, :confidence, :reasoning, 'OPEN')
                        """)
                .param("id", id)
                .param("tx", transactionId)
                .param("reason", reason)
                .param("invoices", investigation.map(Investigation::invoiceNumbers).orElse(List.of())
                        .toArray(String[]::new))
                .param("confidence", investigation.map(Investigation::confidence).orElse(null))
                .param("reasoning", investigation.map(Investigation::reasoning).orElse(null))
                .update();
        return id;
    }

    List<ReviewTask> open() {
        return jdbc.sql("SELECT * FROM review_tasks WHERE status = 'OPEN' ORDER BY created_at")
                .query(ReviewQueue::map).list();
    }

    Optional<ReviewTask> find(UUID id) {
        return jdbc.sql("SELECT * FROM review_tasks WHERE id = :id FOR UPDATE")
                .param("id", id).query(ReviewQueue::map).optional();
    }

    void close(UUID id, ReviewTask.Status status, String decidedBy) {
        jdbc.sql("UPDATE review_tasks SET status = :s, decided_by = :by, decided_at = now() WHERE id = :id")
                .param("s", status.name()).param("by", decidedBy).param("id", id)
                .update();
    }

    private static ReviewTask map(ResultSet rs, int row) throws SQLException {
        Array invoices = rs.getArray("proposed_invoices");
        return new ReviewTask(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getString("reason"),
                invoices == null ? List.of() : Arrays.asList((String[]) invoices.getArray()),
                rs.getObject("agent_confidence", Double.class),
                rs.getString("agent_reasoning"),
                ReviewTask.Status.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

}
