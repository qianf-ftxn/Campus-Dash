package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.wallet.ports.WalletQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWalletQueryAdapter implements WalletQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcWalletQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BalanceView> findBalance(long ownerId) {
        List<BalanceView> list = jdbc.query("""
                SELECT available, frozen FROM wallet_account
                 WHERE owner_id = ? AND owner_type = 'USER'
                """, (rs, n) -> new BalanceView(rs.getLong("available"), rs.getLong("frozen")), ownerId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LedgerView> ledger(long ownerId, int page, int size) {
        // Personal cash flow follows the USER account owner; escrow and platform balancing
        // entries share a business user_id but must not appear as personal income/expense.
        return jdbc.query("""
                SELECT l.created_at, l.direction, l.amount, l.ref_type, l.ref_id, l.biz_no
                  FROM wallet_ledger l JOIN wallet_account a ON a.id = l.account_id
                 WHERE a.owner_id = ? AND a.owner_type = 'USER'
                 ORDER BY l.created_at DESC, l.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new LedgerView(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("direction"), rs.getLong("amount"),
                        rs.getString("ref_type"), rs.getLong("ref_id"), rs.getString("biz_no")),
                ownerId, size, page * size);
    }
}
