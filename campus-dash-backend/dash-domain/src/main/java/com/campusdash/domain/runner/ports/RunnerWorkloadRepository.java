package com.campusdash.domain.runner.ports;

public interface RunnerWorkloadRepository {
    boolean tryAcquire(long runnerId, int maxOngoing);
    void assign(long errandId, int round, long runnerId);
    boolean release(long errandId, int round, String releasedBy);
    int ongoingCount(long runnerId);
}
