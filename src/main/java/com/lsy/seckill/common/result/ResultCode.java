package com.lsy.seckill.common.result;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一业务状态码
 *
 * <p>约定：2xx 表示成功；4xx/5xx 对齐 HTTP 语义；1xxx 起为业务自定义码。
 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    /* ========== 通用 ========== */
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(500, "系统繁忙，请稍后再试"),

    /* ========== 秒杀业务：1xxx ========== */
    SECKILL_NOT_START(1001, "秒杀活动尚未开始"),
    SECKILL_ENDED(1002, "秒杀活动已经结束"),
    SECKILL_STOCK_EMPTY(1003, "商品库存不足"),
    SECKILL_REPEAT(1004, "您已参与过本次秒杀"),

    /* ========== 商品业务：2xxx ========== */
    GOODS_NOT_FOUND(2001, "商品不存在"),
    GOODS_OFF_SHELF(2002, "商品已下架");

    /** 业务码 */
    private final int code;

    /** 提示信息 */
    private final String message;
}
