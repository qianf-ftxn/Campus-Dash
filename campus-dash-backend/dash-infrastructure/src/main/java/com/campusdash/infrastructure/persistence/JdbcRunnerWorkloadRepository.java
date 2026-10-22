package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.runner.ports.RunnerWorkloadRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRunnerWorkloadRepository implements RunnerWorkloadRepository {
    private final JdbcTemplate jdbc;

    public JdbcRunnerWorkloadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(long runnerId, int maxOngoing) {
        jdbc.update("""
                INSERT INTO runner_workload(runner_id,ongoing_count,max_ongoing,version)
                VALUES (?,0,?,0)
                ON DUPLICATE KEY UPDATE
                  max_ongoing=GREATEST(ongoing_count, ?)
                """, runnerId, maxOngoing, maxOngoing);
        return jdbc.update("""
                UPDATE runner_workload
                   SET ongoing_count=ongoing_count+1,version=version+1
                 WHERE runner_id=? AND ongoing_count<max_ongoing
                """, runnerId) == 1;
    }

    @Override
    public void assign(long errandId, int round, long runnerId) {
        jdbc.update("""
                INSERT INTO runner_capacity_assignment(errand_id,round,runner_id,status)
                VALUES (?,?,?,'OCCUPIED')
                """, errandId, round, runnerId);
    }

    @Override
    public boolean release(long errandId, int round, String releasedBy) {
        var runners = jdbc.query("""
                SELECT runner_id FROM runner_capacity_assignment
                 WHERE errand_id=? AND round=? AND status='OCCUPIED'
                """, (rs, n) -> rs.getLong(1), errandId, round);
        if (runners.isEmpty()) return false;
        long runnerId = runners.getFirst();
        int changed = jdbc.update("""
                UPDATE runner_capacity_assignment
                   SET status='RELEASED',released_by=?,released_at=NOW(3)
                 WHERE errand_id=? AND round=? AND status='OCCUPIED'
                """, releasedBy, errandId, round);
        if (changed == 0) return false;
        jdbc.update("""
                UPDATE runner_workload
                   SET ongoing_count=ongoing_count-1,version=version+1
                 WHERE runner_id=? AND ongoing_count>0
                """, runnerId);
        return true;
    }

    @Override
    public int ongoingCount(long runnerId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(ongoing_count),0) FROM runner_workload WHERE runner_id=?",
                Integer.class, runnerId);
        return value == null ? 0 : value;
    }
}
