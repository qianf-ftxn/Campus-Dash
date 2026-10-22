package com.campusdash.infrastructure.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
class NotificationReadScopeTest {
    @Test void read_operations_cannot_change_another_users_notifications() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:notification-read;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE notification(id BIGINT,user_id BIGINT,errand_id BIGINT,type VARCHAR,content VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,read_flag INT DEFAULT 0)");
        jdbc.update("INSERT INTO notification(id,user_id,errand_id,type,content) VALUES(1,1001,42,'SETTLED','one'),(2,2001,42,'SETTLED','other'),(3,1001,43,'REFUNDED','two')");
        var query = new JdbcNotificationQueryAdapter(jdbc);
        assertEquals(0,query.markRead(1001,2)); assertEquals(1,query.unreadCount(2001));
        assertEquals(2,query.markAllRead(1001)); assertEquals(0,query.unreadCount(1001)); assertEquals(1,query.unreadCount(2001));
        assertTrue(query.list(1001,0,1).getFirst().read());
        assertEquals(3,query.list(1001,0,1).getFirst().id()); assertEquals(1,query.list(1001,1,1).getFirst().id());
    }
}
