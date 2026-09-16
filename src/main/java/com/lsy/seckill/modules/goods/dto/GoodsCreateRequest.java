package com.lsy.seckill.modules.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增商品请求
 */
@Data
public class GoodsCreateRequest {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称不能超过 128 个字符")
    private String name;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格不能低于 0.01 元")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;
}
