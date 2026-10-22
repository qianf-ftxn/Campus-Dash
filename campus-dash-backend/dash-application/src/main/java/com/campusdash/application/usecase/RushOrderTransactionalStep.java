package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.ports.RushCampaignRepository;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RushOrderTransactionalStep {
    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final RushCampaignRepository campaigns;
    private final RushRequestRepository requests;
    private final WalletRepository wallets;
    private final ErrandRepository errands;
    private final SnowflakeIdGenerator ids;

    public RushOrderTransactionalStep(RushCampaignRepository campaigns,
                                      RushRequestRepository requests,
                                      WalletRepository wallets,
                                      ErrandRepository errands,
                                      SnowflakeIdGenerator ids) {
        this.campaigns = campaigns;
        this.requests = requests;
        this.wallets = wallets;
        this.errands = errands;
        this.ids = ids;
    }

    @Transactional(rollbackFor = Exception.class)
    public long create(RushOrderMessage message) {
        var existing = requests.findByRequestId(message.requestId());
        if (existing.isPresent()) {
            if (existing.get().status() == RushRequestStatus.SUCCESS && existing.get().errandId() != null) {
                return existing.get().errandId();
            }
            throw new BizException(ErrorCode.RUSH_PROCESS_CONFLICT, "request正在被其他消费者处理");
        }

        RushRequestRepository.RushOrderData data = data(message);
        requests.insertReserved(data);
        if (campaigns.casIncrementSold(message.campaignId()) == 0) {
            throw new BizException(ErrorCode.RUSH_SOLD_OUT);
        }

        Money reward = Money.ofCents(message.rewardCents());
        WalletAccount publisher = wallets.findByOwner(message.userId(), AccountType.USER)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND));
        WalletAccount escrow = wallets.findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (wallets.casDebit(publisher.id(), reward) == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        long errandId = ids.nextId();
        String bizNo = LedgerEntry.rushEscrowBizNo(message.requestId());
        wallets.insertLedger(new LedgerEntry(ids.nextId(), bizNo, publisher.id(), message.userId(),
                LedgerEntry.Direction.DEBIT, reward, publisher.available().minus(reward),
                LedgerEntry.RefType.ESCROW, errandId));
        wallets.insertLedger(new LedgerEntry(ids.nextId(), bizNo, escrow.id(), message.userId(),
                LedgerEntry.Direction.CREDIT, reward, escrow.available().plus(reward),
                LedgerEntry.RefType.ESCROW, errandId));
        wallets.casCredit(escrow.id(), reward);
        wallets.insertEscrow(EscrowOrder.held(ids.nextId(), message.campusId(), errandId, message.userId(), reward));

        Errand errand = Errand.draft(errandId, message.campusId(), message.userId(),
                ErrandType.BUY, message.title(), reward, 1);
        errands.insertRush(errand, message.requestId());
        long before = errand.version();
        errand.publish(before);
        if (errands.casPublish(errandId, before) == 0) {
            throw new BizException(ErrorCode.RUSH_PROCESS_CONFLICT, "发布活动任务CAS失败");
        }
        errands.appendStatusLog(errandId, ErrandStatus.DRAFT, ErrandStatus.PUBLISHED, 0, message.userId());
        requests.markSuccess(message.requestId(), errandId);
        return errandId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void persistFailure(RushOrderMessage message, RushRequestStatus status, ErrorCode code) {
        requests.saveFailure(data(message), status, code.name());
        requests.markFailure(message.requestId(), status, code.name());
    }

    private static RushRequestRepository.RushOrderData data(RushOrderMessage m) {
        return new RushRequestRepository.RushOrderData(m.requestId(), m.campaignId(), m.campusId(),
                m.userId(), m.title(), m.rewardCents(), m.acceptedAt());
    }
}
