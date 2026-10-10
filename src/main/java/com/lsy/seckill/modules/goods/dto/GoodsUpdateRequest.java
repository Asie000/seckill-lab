package com.lsy.seckill.modules.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新商品请求
 */
@Data
public class GoodsUpdateRequest {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称不能超过 128 个字符")
    private String name;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格不能低于 0.01 元")
    private BigDecimal price;

    /** 1-上架 0-下架 */
    private Integer status;
}
