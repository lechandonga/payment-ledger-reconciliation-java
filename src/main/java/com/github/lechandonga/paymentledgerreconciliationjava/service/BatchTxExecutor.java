package com.github.lechandonga.paymentledgerreconciliationjava.service;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.ReconRepository;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 把「抢占批次 + 对账 + 结算 + 完成标记」放在同一个数据库事务里：
 * 并发线程在唯一键 batch_no 上互斥，抢不到的事务拿到 DuplicateKey 后立即回滚并得到明确拒绝。
 */
@Component
public class BatchTxExecutor {

    private final ReconRepository recon;
    private final ReconciliationService reconciliationService;

    public BatchTxExecutor(ReconRepository recon, @Lazy ReconciliationService reconciliationService) {
        this.recon = recon;
        this.reconciliationService = reconciliationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> acquireAndRun(String batchNo, List<String> merchantIds,
                                             List<Requests.ChannelEntry> entries, String inputHash) {
        if (!recon.tryAcquire(batchNo)) {
            throw new BusinessException(ErrorCode.BATCH_CONCURRENT,
                    "批次 " + batchNo + " 已被另一并发请求抢占，本请求拒绝");
        }
        return reconciliationService.execute(batchNo, merchantIds, entries, inputHash);
    }
}
