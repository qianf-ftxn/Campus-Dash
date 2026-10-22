package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.campaign.model.RushCampaign;
import com.campusdash.domain.campaign.model.RushCampaignStatus;
import com.campusdash.domain.campaign.ports.RushCampaignRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRushCampaignRepository implements RushCampaignRepository {
    private final JdbcTemplate jdbc;

    public JdbcRushCampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RushCampaign> MAPPER = (rs, n) -> new RushCampaign(
            rs.getLong("id"), rs.getLong("campus_id"), rs.getString("name"),
            rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
            rs.getInt("quota_total"), rs.getInt("sold_count"),
            RushCampaignStatus.valueOf(rs.getString("status")), rs.getLong("version"));

    @Override
    public Optional<RushCampaign> findById(long campaignId) {
        List<RushCampaign> rows = jdbc.query("SELECT * FROM rush_campaign WHERE id=?", MAPPER, campaignId);
        return rows.stream().findFirst();
    }

    @Override
    public List<RushCampaign> findByCampus(long campusId) {
        return jdbc.query("""
                SELECT * FROM rush_campaign WHERE campus_id=?
                ORDER BY start_at DESC, id DESC
                """, MAPPER, campusId);
    }

    @Override
    public int casIncrementSold(long campaignId) {
        return jdbc.update("""
                UPDATE rush_campaign
                   SET sold_count=sold_count+1, version=version+1
                 WHERE id=? AND status='ENABLED' AND sold_count<quota_total
                """, campaignId);
    }

    @Override
    public long countSuccessfulRequests(long campaignId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rush_request WHERE campaign_id=? AND status='SUCCESS'",
                Long.class, campaignId);
        return value == null ? 0L : value;
    }
}
