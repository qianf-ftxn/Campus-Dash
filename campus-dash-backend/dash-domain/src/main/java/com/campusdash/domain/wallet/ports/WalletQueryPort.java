package com.campusdash.domain.wallet.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 钱包读模型端口。
 *
 * "我的任务""钱包流水"这类读需求不经过聚合根，直接走读模型——
 * CQRS 的最小形态：写走聚合（状态机、CAS），读走查询。
 */
public interface WalletQueryPort {

    /** 余额 */
    Optional<BalanceView> findBalance(long ownerId);

    /** 个人账户流水分页，排除平台与托管账户的配对分录 */
    List<LedgerView> ledger(long ownerId, int page, int size);

    record BalanceView(long availableCents, long frozenCents) {}

    record LedgerView(Instant time, String direction, long amountCents,
                      String refType, long refId, String bizNo) {}
}
