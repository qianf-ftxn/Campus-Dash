package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.GrabRecord;
import com.campusdash.domain.grab.ports.GrabRecordRepository;
import com.campusdash.domain.runner.ports.RunnerWorkloadRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class GrabTransactionalStepCapacityTest {
    @Test
    void capacity_full_stops_before_errand_cas() {
        int[] casCalls = {0};
        ErrandRepository errands = errandRepository((id, runner, version) -> {
            casCalls[0]++;
            return 1;
        });
        FakeWorkload workload = new FakeWorkload(false);
        GrabTransactionalStep step = new GrabTransactionalStep(errands, records(), workload);

        var result = step.lockAndRecord(1, 10, 20, 0, 1, 0, 30,
                ErrandStatus.PUBLISHED, 5);

        assertEquals(GrabTransactionalStep.LockResult.CAPACITY_FULL, result);
        assertEquals(0, casCalls[0]);
        assertEquals(0, workload.assignCalls);
    }

    @Test
    void errand_conflict_throws_so_transaction_proxy_can_rollback_capacity() {
        FakeWorkload workload = new FakeWorkload(true);
        GrabTransactionalStep step = new GrabTransactionalStep(
                errandRepository((id, runner, version) -> 0), records(), workload);

        assertThrows(GrabTransactionalStep.ErrandConflictException.class,
                () -> step.lockAndRecord(1, 10, 20, 0, 1, 0, 30,
                        ErrandStatus.PUBLISHED, 5));
        assertEquals(0, workload.assignCalls);
    }

    @Test
    void success_creates_capacity_assignment() {
        FakeWorkload workload = new FakeWorkload(true);
        GrabTransactionalStep step = new GrabTransactionalStep(
                errandRepository((id, runner, version) -> 1), records(), workload);

        assertEquals(GrabTransactionalStep.LockResult.SUCCESS,
                step.lockAndRecord(1, 10, 20, 0, 1, 0, 30,
                        ErrandStatus.PUBLISHED, 5));
        assertEquals(1, workload.assignCalls);
    }

    private static ErrandRepository errandRepository(Cas cas) {
        return (ErrandRepository) Proxy.newProxyInstance(
                GrabTransactionalStepCapacityTest.class.getClassLoader(),
                new Class<?>[]{ErrandRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "casLockForRunner" -> cas.apply((long) args[0], (long) args[1], (long) args[2]);
                    case "appendStatusLog" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static GrabRecordRepository records() {
        return new GrabRecordRepository() {
            @Override public void insert(GrabRecord record) {}
            @Override public int countGrabbed(long campusId, long errandId) { return 0; }
        };
    }

    private interface Cas { int apply(long id, long runner, long version); }

    private static final class FakeWorkload implements RunnerWorkloadRepository {
        final boolean acquired;
        int assignCalls;
        FakeWorkload(boolean acquired) { this.acquired = acquired; }
        @Override public boolean tryAcquire(long runnerId, int maxOngoing) { return acquired; }
        @Override public void assign(long errandId, int round, long runnerId) { assignCalls++; }
        @Override public boolean release(long errandId, int round, String by) { return false; }
        @Override public int ongoingCount(long runnerId) { return 0; }
    }
}
