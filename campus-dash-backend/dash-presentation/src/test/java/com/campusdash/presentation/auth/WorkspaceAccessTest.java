package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.query.WorkspaceRolePolicy;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAccessTest {
    private final WorkspaceAccess access = new WorkspaceAccess(new WorkspaceRolePolicy(9001L, "1001,1002"));
    private void denied(long user, String method, String path, String mineRole, String status) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> access.check(user, method, path, mineRole, status)).code());
    }
    @Test void publisher_cannot_grab_or_read_runner_workspace() {
        for (String action : new String[]{"grab", "confirm", "pickup", "deliver", "arbitrate"})
            denied(1001, "POST", "/api/errands/42/" + action, null, null);
        denied(1001, "GET", "/api/errands", null, null);
        denied(1001, "GET", "/api/errands/mine", "GRABBED_BY_ME", null);
        denied(1001, "GET", "/api/credit/self", null, null);
        assertDoesNotThrow(() -> access.check(1001, "POST", "/api/errands", null, null));
        assertDoesNotThrow(() -> access.check(1001, "GET", "/api/errands/mine", null, null));
        assertDoesNotThrow(() -> access.check(1001, "POST", "/api/rush-campaigns/1/orders", null, null));
    }
    @Test void runner_cannot_publish_or_settle_or_query_other_states() {
        denied(2001, "POST", "/api/errands", null, null);
        denied(2001, "POST", "/api/errands/42/settle", null, null);
        denied(2001, "POST", "/api/errands/42/cancel", null, null);
        denied(2001, "POST", "/api/rush-campaigns/1/orders", null, null);
        denied(2001, "GET", "/api/errands/mine", null, null);
        denied(2001, "GET", "/api/errands", null, "DISPUTED");
        assertDoesNotThrow(() -> access.check(2001, "GET", "/api/errands", null, "PUBLISHED"));
        assertDoesNotThrow(() -> access.check(2001, "POST", "/api/errands/42/grab", null, null));
        assertDoesNotThrow(() -> access.check(2001, "GET", "/api/errands/mine", "GRABBED_BY_ME", null));
    }
    @Test void arbitrator_has_only_management_capabilities() {
        denied(9001, "POST", "/api/errands", null, null);
        denied(9001, "POST", "/api/errands/42/grab", null, null);
        denied(9001, "POST", "/api/errands/42/dispute", null, null);
        denied(9001, "GET", "/api/errands/mine", null, null);
        denied(9001, "GET", "/api/wallet", null, null);
        assertDoesNotThrow(() -> access.check(9001, "GET", "/api/errands", null, "DISPUTED"));
        assertDoesNotThrow(() -> access.check(9001, "POST", "/api/errands/42/arbitrate", null, null));
    }
}
