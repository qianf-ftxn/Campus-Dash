package com.campusdash.infrastructure.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
class PersonalLedgerQueryTest {
    @Test void excludes_escrow_and_platform_balancing_entries_and_follows_actual_account_owner() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:personal-ledger;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE wallet_account(id BIGINT,owner_id BIGINT,owner_type VARCHAR,available BIGINT DEFAULT 0,frozen BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE wallet_ledger(id BIGINT,account_id BIGINT,user_id BIGINT,direction VARCHAR,amount BIGINT,ref_type VARCHAR,ref_id BIGINT,biz_no VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO wallet_account(id,owner_id,owner_type) VALUES (1,1001,'USER'),(2,-1,'ESCROW'),(3,2001,'USER'),(4,0,'PLATFORM')");
        jdbc.update("INSERT INTO wallet_ledger(id,account_id,user_id,direction,amount,ref_type,ref_id,biz_no) VALUES " +
                "(1,1,1001,'DEBIT',100,'ESCROW',42,'hold'),(2,2,1001,'CREDIT',100,'ESCROW',42,'hold')," +
                "(3,2,1001,'DEBIT',100,'SETTLE',42,'settle'),(4,3,1001,'CREDIT',95,'SETTLE',42,'settle')," +
                "(5,4,1001,'CREDIT',5,'SETTLE',42,'settle')");
        var query = new JdbcWalletQueryAdapter(jdbc);
        var publisher = query.ledger(1001,0,20);
        assertEquals(1,publisher.size()); assertEquals("DEBIT",publisher.getFirst().direction()); assertEquals(100,publisher.getFirst().amountCents());
        var runner = query.ledger(2001,0,20);
        assertEquals(1,runner.size()); assertEquals(95,runner.getFirst().amountCents());
    }
}
