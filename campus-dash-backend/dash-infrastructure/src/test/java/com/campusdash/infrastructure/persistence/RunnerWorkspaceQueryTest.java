package com.campusdash.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RunnerWorkspaceQueryTest {
    @Test void assigned_and_completed_orders_remain_but_transferred_orders_do_not() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:workspace-query;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE errand (id BIGINT, campus_id BIGINT DEFAULT 1, publisher_id BIGINT DEFAULT 1001, " +
                "type VARCHAR DEFAULT 'DELIVERY', title VARCHAR DEFAULT '取快递', reward_amount BIGINT DEFAULT 100, " +
                "slot_total INT DEFAULT 1, slot_taken INT DEFAULT 1, grabber_id BIGINT, status VARCHAR, " +
                "round INT DEFAULT 0, version BIGINT DEFAULT 0, locked_at TIMESTAMP, delivered_at TIMESTAMP, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE grab_record (errand_id BIGINT, runner_id BIGINT)");
        jdbc.update("INSERT INTO errand(id,grabber_id,status,created_at) VALUES (1,2001,'LOCKED',CURRENT_TIMESTAMP)," +
                "(2,2002,'ACCEPTED',CURRENT_TIMESTAMP),(3,NULL,'PUBLISHED',CURRENT_TIMESTAMP),(4,2001,'SETTLED',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO grab_record VALUES (1,2001),(2,2001),(3,2001),(4,2001)");
        var query = new JdbcErrandQueryAdapter(jdbc);
        assertEquals(List.of(1L, 4L), query.personalOrders(2001L, true, "priority", 0, 20).stream().map(e -> e.id()).toList());
        assertEquals(2, query.creationTimes(List.of(1L, 4L)).size());
        assertEquals(List.of(4L, 1L), query.listByRunner(2001L, 0, 20).stream().map(e -> e.id()).toList());
        assertEquals(4L, query.listByRunner(2001L, 0, 1).getFirst().id());
        assertEquals(1L, query.listByRunner(2001L, 1, 1).getFirst().id());
    }
}
