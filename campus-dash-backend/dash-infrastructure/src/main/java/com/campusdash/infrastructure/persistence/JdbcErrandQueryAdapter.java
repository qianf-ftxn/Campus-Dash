package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class JdbcErrandQueryAdapter implements ErrandQueryPort {

    /** 读模型的字段映射与写仓储共用一套 rehydrate 逻辑，避免两边漂移 */
    private static final RowMapper<Errand> MAPPER = (rs, n) -> Errand.rehydrate(
            rs.getLong("id"), rs.getLong("campus_id"), rs.getLong("publisher_id"),
            com.campusdash.domain.errand.model.ErrandType.valueOf(rs.getString("type")),
            rs.getString("title"),
            com.campusdash.shared.Money.ofCents(rs.getLong("reward_amount")),
            rs.getInt("slot_total"),
            rs.getObject("grabber_id") == null ? null : rs.getLong("grabber_id"),
            com.campusdash.domain.errand.model.ErrandStatus.valueOf(rs.getString("status")),
            rs.getInt("slot_taken"), rs.getInt("round"), rs.getLong("version"),
            rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcErrandQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Errand> list(long campusId, String status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, campusId, status, size, page * size);
    }

    @Override
    public List<Errand> search(long campusId, String status, String type, String keyword, String sort, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM errand WHERE campus_id = ? AND status = ?");
        java.util.List<Object> args = new java.util.ArrayList<>(java.util.List.of(campusId, status));
        if (type != null) { sql.append(" AND type = ?"); args.add(type); }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND LOWER(title) LIKE ? ESCAPE '!'");
            args.add("%" + keyword.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        sql.append("reward".equals(sort) ? " ORDER BY reward_amount DESC, created_at DESC, id DESC" : " ORDER BY created_at DESC, id DESC");
        sql.append(" LIMIT ? OFFSET ?"); args.add(size); args.add((long) page * size);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) {
        if (beforeCreatedAt == null || beforeId == null) {
            return jdbc.query("""
                    SELECT * FROM errand
                     WHERE campus_id = ? AND status = ?
                     ORDER BY created_at DESC, id DESC
                     LIMIT ?
                    """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                            rs.getTimestamp("created_at").toInstant()),
                    campusId, status, size);
        }
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                   AND (created_at < ? OR (created_at = ? AND id < ?))
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                        rs.getTimestamp("created_at").toInstant()),
                campusId, status,
                java.sql.Timestamp.from(beforeCreatedAt), java.sql.Timestamp.from(beforeCreatedAt),
                beforeId, size);
    }

    @Override
    public List<Errand> listByPublisher(long publisherId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE publisher_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, publisherId, size, page * size);
    }

    @Override
    public List<Errand> listByRunner(long runnerId, int page, int size) {
        // 我的接单按当前归属查询，包含本人已完成的订单。超时后转派给他人的订单不混入履约列表。
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE grabber_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, runnerId, size, page * size);
    }

    @Override
    public List<Errand> personalOrders(long userId, boolean runner, String sort, int page, int size) {
        String owner = runner ? "grabber_id" : "publisher_id";
        String priority = runner
                ? "CASE status WHEN 'LOCKED' THEN 0 WHEN 'ACCEPTED' THEN 1 WHEN 'PICKED_UP' THEN 2 WHEN 'DISPUTED' THEN 3 WHEN 'DELIVERED' THEN 4 ELSE 5 END"
                : "CASE status WHEN 'DELIVERED' THEN 0 WHEN 'DISPUTED' THEN 1 WHEN 'LOCKED' THEN 2 WHEN 'ACCEPTED' THEN 2 WHEN 'PICKED_UP' THEN 2 WHEN 'PUBLISHED' THEN 3 ELSE 5 END";
        String order = "priority".equals(sort) ? priority + ", created_at DESC, id DESC"
                : "reward".equals(sort) ? "reward_amount DESC, created_at DESC, id DESC" : "created_at DESC, id DESC";
        return jdbc.query("SELECT * FROM errand WHERE " + owner + " = ? ORDER BY " + order + " LIMIT ? OFFSET ?",
                MAPPER, userId, size, (long) page * size);
    }

    @Override
    public java.util.Map<Long, Instant> creationTimes(List<Long> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        var times = new java.util.HashMap<Long, Instant>();
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT id, created_at FROM errand WHERE id IN (" + marks + ")", rs -> {
            var time = rs.getTimestamp("created_at");
            if (time != null) times.put(rs.getLong("id"), time.toInstant());
        }, ids.toArray());
        return times;
    }

    @Override
    public List<StatusChange> statusLog(long campusId, long errandId) {
        return jdbc.query("""
                SELECT created_at, from_status, to_status, round, operator_id
                  FROM errand_status_log
                 WHERE campus_id = ? AND errand_id = ?
                 ORDER BY created_at, id
                """, (rs, n) -> new StatusChange(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getInt("round"), rs.getLong("operator_id")), campusId, errandId);
    }

    @Override
    public List<Long> sampleIds(int limit) {
        return jdbc.queryForList("SELECT id FROM errand ORDER BY RAND() LIMIT ?", Long.class, limit);
    }

    @Override
    public int countOngoingByRunner(long runnerId) {
        // 在途 = 已确认接单但还没送达的任务（ACCEPTED/PICKED_UP）。
        // LOCKED 不算：还没确认，可能超时流转走
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM errand
                 WHERE grabber_id = ? AND status IN ('ACCEPTED', 'PICKED_UP')
                """, Integer.class, runnerId);
        return n == null ? 0 : n;
    }
}
