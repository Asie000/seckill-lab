package com.lsy.seckill.modules.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 普通下单请求
 */
@Data
public class OrderCreateRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotNull(message = "goodsId 不能为空")
    private Long goodsId;

    @Min(value = 1, message = "购买数量至少为 1")
    private Integer qty = 1;
}
