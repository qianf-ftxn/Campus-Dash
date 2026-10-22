package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.*;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRolePolicyTest {
    private final WorkspaceRolePolicy roles = new WorkspaceRolePolicy(9001L, "1001, 1002");
    private final ErrandActionResolver resolver = new ErrandActionResolver(roles);
    private Errand order(ErrandStatus status, Long runner) {
        return Errand.rehydrate(42L, 1L, 1001L, ErrandType.DELIVERY, "取快递", Money.ofCents(1500),
                1, runner, status, runner == null ? 0 : 1, 0, 0, Instant.now());
    }
    @Test void only_runner_gets_grab_action() {
        var order = order(ErrandStatus.PUBLISHED, null);
        assertEquals(List.of("CANCEL"), resolver.resolve(order, 1001L).actions());
        assertFalse(resolver.resolve(order, 1002L).actions().contains("GRAB"));
        assertEquals(List.of("GRAB"), resolver.resolve(order, 2001L).actions());
        assertTrue(resolver.resolve(order, 9001L).actions().isEmpty());
    }
    @Test void lifecycle_actions_still_require_order_ownership() {
        var locked = order(ErrandStatus.LOCKED, 2001L);
        assertTrue(resolver.resolve(locked, 2001L).actions().contains("CONFIRM"));
        assertTrue(resolver.resolve(locked, 2002L).actions().isEmpty());
        var delivered = order(ErrandStatus.DELIVERED, 2001L);
        assertTrue(resolver.resolve(delivered, 1001L).actions().contains("SETTLE"));
        assertFalse(resolver.resolve(delivered, 1002L).actions().contains("SETTLE"));
        var disputed = order(ErrandStatus.DISPUTED, 2001L);
        assertEquals(List.of("ARBITRATE"), resolver.resolve(disputed, 9001L).actions());
        assertTrue(resolver.resolve(disputed, 1001L).actions().isEmpty());
    }
    @Test void configured_publishers_and_arbitrator_are_server_owned() {
        assertEquals(WorkspaceRolePolicy.Role.PUBLISHER, roles.roleFor(1002L));
        assertEquals(WorkspaceRolePolicy.Role.RUNNER, roles.roleFor(30000L));
        assertEquals(WorkspaceRolePolicy.Role.ARBITRATOR, roles.roleFor(9001L));
    }
}
