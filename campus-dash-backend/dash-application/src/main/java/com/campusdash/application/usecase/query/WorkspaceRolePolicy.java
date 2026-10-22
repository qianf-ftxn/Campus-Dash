package com.campusdash.application.usecase.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Server-owned workspace roles for the existing demo account model.
 * Additional publisher accounts must be configured explicitly. Other demo/load-test accounts
 * retain the existing runner role. Production onboarding should replace this registry.
 */
@Component
public class WorkspaceRolePolicy {
    public enum Role { PUBLISHER, RUNNER, ARBITRATOR }
    private final long arbitratorId;
    private final Set<Long> publisherIds;

    public WorkspaceRolePolicy(@Value("${dash.auth.arbitrator-id:9001}") long arbitratorId,
                               @Value("${dash.auth.publisher-ids:1001}") String publishers) {
        this.arbitratorId = arbitratorId;
        this.publisherIds = Arrays.stream(publishers.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public Role roleFor(long userId) {
        if (userId == arbitratorId) return Role.ARBITRATOR;
        return publisherIds.contains(userId) ? Role.PUBLISHER : Role.RUNNER;
    }
}
