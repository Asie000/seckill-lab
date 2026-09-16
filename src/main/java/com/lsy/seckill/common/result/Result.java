package com.lsy.seckill.common.result;

import java.io.Serializable;

/**
 * 统一响应结构
 *
 * @param code    业务码，200 表示成功
 * @param message 提示信息
 * @param data    业务数据
 * @param <T>     数据类型
 */
public record Result<T>(int code, String message, T data) implements Serializable {

    /** 成功（无数据） */
    public static Result<Void> ok() {
        return ok(null);
    }

    /** 成功（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 失败（使用预定义状态码） */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /** 失败（预定义状态码 + 自定义信息） */
    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /** 失败（完全自定义） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 是否成功 */
    public boolean isSuccess() {
        return code == ResultCode.SUCCESS.getCode();
    }
}
