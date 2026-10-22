package com.campusdash.domain.wallet.model;

import com.campusdash.shared.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 资金流水（事实来源）。每笔资金动作必须记一借一贷两条，且金额相等——
 * 这样"钱不会凭空产生也不会凭空消失"就变成了一个可用 SQL 验证的不变式。
 *
 * bizNo 上有唯一索引做幂等：重复请求会撞唯一索引，应用层捕获后返回"已处理"，
 * 比"先查再插"可靠，因为唯一索引的判定与插入是数据库层的一个原子动作。
 */
public record LedgerEntry(long id, String bizNo, long accountId, long userId, Direction direction,
                          Money amount, Money balanceAfter, RefType refType, long refId) {

    public enum Direction {
        /** 借：资金流出该账户 */
        DEBIT,
        /** 贷：资金流入该账户 */
        CREDIT
    }

    public enum RefType {
        ESCROW, SETTLE, REFUND, RECHARGE, WITHDRAW
    }

    /** 托管场景的幂等键：一个任务只能托管一次 */
    public static String escrowBizNo(long errandId) {
        return "escrow:" + errandId;
    }

    /**
     * 活动订单在生成errandId前就有稳定requestId，用它保证重复消费不重复扣款。
     * requestId本身允许64字符，不能再直接拼前缀写入VARCHAR(64)；使用SHA-256的
     * Base64URL摘要后，完整bizNo固定为55字符，仍保留确定性和足够的唯一性。
     */
    public static String rushEscrowBizNo(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestId.getBytes(StandardCharsets.UTF_8));
            return "rush:escrow:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256", impossible);
        }
    }

    /** 结算幂等键：一个任务只能结算一次。并发重复结算靠这个键撞唯一索引挡住 */
    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    /** 退款幂等键：一个任务只能退款一次 */
    public static String refundBizNo(long errandId) {
        return "refund:" + errandId;
    }
}
