package com.github.lechandonga.paymentledgerreconciliationjava.domain;

public enum ErrorCode {
    PAYMENT_NOT_FOUND("支付单不存在"),
    REFUND_NOT_FOUND("退款单不存在"),
    IDEMPOTENT_PARAM_MISMATCH("幂等键相同但请求参数与首次不一致"),
    ILLEGAL_TRANSITION("当前状态不允许该操作"),
    INVALID_AMOUNT("金额非法，必须为正数"),
    CAPTURE_EXCEEDS_AUTH("请款金额超过剩余可请款额度"),
    REFUND_EXCEEDS_CAPTURED("退款金额超过剩余可退款额度"),
    MERCHANT_BALANCE_INSUFFICIENT("商户待结算余额不足，退款被拒绝"),
    BATCH_CONCURRENT("同一对账批次正在处理中，并发触发被拒绝"),
    BATCH_INPUT_MISMATCH("批次渠道文件与首次执行不一致"),
    INVALID_REQUEST("请求参数错误");

    public final String message;

    ErrorCode(String message) {
        this.message = message;
    }
}
