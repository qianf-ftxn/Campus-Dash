package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRushRequestRepository implements RushRequestRepository {
    private final JdbcTemplate jdbc;

    public JdbcRushRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RushRequest> MAPPER = (rs, n) -> new RushRequest(
            rs.getString("request_id"), rs.getLong("campaign_id"), rs.getLong("campus_id"),
            rs.getLong("user_id"), RushRequestStatus.valueOf(rs.getString("status")),
            rs.getString("title"), rs.getLong("reward_amount"),
            rs.getObject("errand_id") == null ? null : rs.getLong("errand_id"),
            rs.getString("error_code"), rs.getTimestamp("accepted_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    @Override
    public Optional<RushRequest> findByRequestId(String requestId) {
        List<RushRequest> rows = jdbc.query("SELECT * FROM rush_request WHERE request_id=?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RushRequest> findByCampaignAndUser(long campaignId, long userId) {
        List<RushRequest> rows = jdbc.query(
                "SELECT * FROM rush_request WHERE campaign_id=? AND user_id=?", MAPPER, campaignId, userId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertReserved(RushOrderData d) {
        jdbc.update("""
                INSERT INTO rush_request
                  (request_id,campaign_id,campus_id,user_id,status,title,reward_amount,accepted_at)
                VALUES (?,?,?,?,'RESERVED',?,?,?)
                """, d.requestId(), d.campaignId(), d.campusId(), d.userId(),
                d.title(), d.rewardCents(), java.sql.Timestamp.from(d.acceptedAt()));
    }

    @Override
    public void markSuccess(String requestId, long errandId) {
        jdbc.update("""
                UPDATE rush_request SET status='SUCCESS',errand_id=?,error_code=NULL
                 WHERE request_id=? AND status='RESERVED'
                """, errandId, requestId);
    }

    @Override
    public void saveFailure(RushOrderData d, RushRequestStatus status, String errorCode) {
        jdbc.update("""
                INSERT IGNORE INTO rush_request
                  (request_id,campaign_id,campus_id,user_id,status,title,reward_amount,error_code,accepted_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, d.requestId(), d.campaignId(), d.campusId(), d.userId(), status.name(),
                d.title(), d.rewardCents(), errorCode, java.sql.Timestamp.from(d.acceptedAt()));
    }

    @Override
    public void markFailure(String requestId, RushRequestStatus status, String errorCode) {
        jdbc.update("""
                UPDATE rush_request SET status=?,error_code=?
                 WHERE request_id=? AND status IN ('QUEUED','RESERVED')
                """, status.name(), errorCode, requestId);
    }

    @Override
    public List<RushRequest> findStaleProcessing(Instant before, int limit) {
        return jdbc.query("""
                SELECT * FROM rush_request
                 WHERE status IN ('QUEUED','RESERVED') AND updated_at<?
                 ORDER BY updated_at LIMIT ?
                """, MAPPER, java.sql.Timestamp.from(before), limit);
    }

    @Override
    public List<RushRequest> findSuccessfulSince(Instant since, int limit) {
        return jdbc.query("""
                SELECT * FROM rush_request
                 WHERE status='SUCCESS' AND updated_at>=?
                 ORDER BY updated_at DESC LIMIT ?
                """, MAPPER, java.sql.Timestamp.from(since), limit);
    }
}
