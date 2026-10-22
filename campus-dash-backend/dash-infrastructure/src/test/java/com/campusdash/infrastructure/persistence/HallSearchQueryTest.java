package com.campusdash.infrastructure.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;
class HallSearchQueryTest {
    @Test void filters_and_reward_sort_apply_before_pagination_and_escape_wildcards() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:hall-search;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE errand(id BIGINT,campus_id BIGINT DEFAULT 1,publisher_id BIGINT DEFAULT 1001,type VARCHAR DEFAULT 'DELIVERY',title VARCHAR,reward_amount BIGINT,slot_total INT DEFAULT 1,slot_taken INT DEFAULT 0,grabber_id BIGINT,status VARCHAR DEFAULT 'PUBLISHED',round INT DEFAULT 0,version BIGINT DEFAULT 0,locked_at TIMESTAMP,delivered_at TIMESTAMP,created_at TIMESTAMP)");
        for(int i=1;i<=25;i++)jdbc.update("INSERT INTO errand(id,title,reward_amount,created_at) VALUES(?,?,?,?)",i,i==1?"Rare_100% parcel":"regular parcel",i==1?5000:100,new Timestamp(1700000000000L+i*1000));
        jdbc.update("INSERT INTO errand(id,campus_id,title,reward_amount,status,created_at) VALUES (26,2,'Rare_100% parcel',9000,'PUBLISHED',CURRENT_TIMESTAMP),(27,1,'Rare_100% parcel',9000,'LOCKED',CURRENT_TIMESTAMP)");
        var query = new JdbcErrandQueryAdapter(jdbc);
        assertEquals(25,query.search(1,"PUBLISHED",null,"","latest",0,20).getFirst().id());
        var match=query.search(1,"PUBLISHED",null,"rare_100%","latest",0,20);
        assertEquals(1,match.size()); assertEquals(1,match.getFirst().id());
        assertEquals(1,query.search(1,"PUBLISHED",null,"","reward",0,20).getFirst().id());
        assertTrue(query.search(1,"PUBLISHED","BUY","","latest",0,20).isEmpty());
        assertEquals(5,query.search(1,"PUBLISHED",null,"","latest",1,20).size());
    }
}
