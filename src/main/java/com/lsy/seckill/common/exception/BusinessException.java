package com.lsy.seckill.common.exception;

import com.lsy.seckill.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出，由 {@link GlobalExceptionHandler} 统一转换为响应
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务码 */
    private final int code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
    }
}
