package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.query.WorkspaceRolePolicy;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Component;
import static com.campusdash.application.usecase.query.WorkspaceRolePolicy.Role.*;

/** Workspace capabilities supplement, rather than replace, order ownership checks. */
@Component
public class WorkspaceAccess {
    private final WorkspaceRolePolicy roles;
    public WorkspaceAccess(WorkspaceRolePolicy roles) { this.roles = roles; }

    public void check(long userId, String method, String path, String mineRole, String listStatus) {
        var role = roles.roleFor(userId);
        boolean allowed = true;
        if ("/api/errands".equals(path)) {
            if ("POST".equals(method)) allowed = role == PUBLISHER;
            else if ("GET".equals(method)) allowed = role == ARBITRATOR ||
                    (role == RUNNER && (listStatus == null || listStatus.isBlank() || "PUBLISHED".equalsIgnoreCase(listStatus)));
        } else if ("/api/errands/mine".equals(path)) {
            String requested = mineRole == null ? "PUBLISHED_BY_ME" : mineRole;
            allowed = role == PUBLISHER && "PUBLISHED_BY_ME".equals(requested) ||
                    role == RUNNER && "GRABBED_BY_ME".equals(requested);
        } else if (path.matches("/api/errands/[^/]+/(grab|confirm|pickup|deliver)")) {
            allowed = role == RUNNER;
        } else if (path.matches("/api/errands/[^/]+/(settle|cancel)")) {
            allowed = role == PUBLISHER;
        } else if (path.matches("/api/errands/[^/]+/arbitrate")) {
            allowed = role == ARBITRATOR;
        } else if (path.matches("/api/errands/[^/]+/dispute")) {
            allowed = role != ARBITRATOR;
        } else if (path.startsWith("/api/rush-campaigns") || path.startsWith("/api/rush-requests")) {
            allowed = role == PUBLISHER;
        } else if (path.startsWith("/api/credit")) {
            allowed = role == RUNNER;
        } else if (role == ARBITRATOR && (path.startsWith("/api/wallet") || path.startsWith("/api/notifications"))) {
            allowed = false;
        }
        if (!allowed) throw new BizException(ErrorCode.FORBIDDEN);
    }
}
