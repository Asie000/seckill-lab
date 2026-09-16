package com.lsy.seckill.modules.goods.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.goods.dto.GoodsCreateRequest;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.service.GoodsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口（示例模块，演示 Controller -> Service -> Mapper 分层）
 */
@RestController
@RequestMapping("/api/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    /** 分页查询商品列表 */
    @GetMapping
    public Result<IPage<Goods>> page(@RequestParam(defaultValue = "1") int pageNum,
                                     @RequestParam(defaultValue = "10") int pageSize,
                                     @RequestParam(required = false) String name) {
        return Result.ok(goodsService.page(pageNum, pageSize, name));
    }

    /** 查询商品详情 */
    @GetMapping("/{id}")
    public Result<Goods> detail(@PathVariable Long id) {
        return Result.ok(goodsService.getById(id));
    }

    /** 新增商品 */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody GoodsCreateRequest request) {
        return Result.ok(goodsService.create(request));
    }

    /**
     * 扣减库存（仅用于入门演示 / 单机压测）
     *
     * <p>正式秒杀场景请改为：Redis 预扣减（Lua 原子脚本）-> 发 MQ -> 异步落库。
     */
    @PostMapping("/{id}/stock/deduct")
    public Result<Void> deductStock(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") int quantity) {
        goodsService.deductStock(id, quantity);
        return Result.ok();
    }
}
