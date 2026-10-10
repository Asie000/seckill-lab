package com.lsy.seckill.modules.goods.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.goods.cache.GoodsCacheService;
import com.lsy.seckill.modules.goods.dto.GoodsCreateRequest;
import com.lsy.seckill.modules.goods.dto.GoodsUpdateRequest;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.service.GoodsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品接口（示例模块，演示 Controller -> Service -> Mapper 分层）
 */
@RestController
@RequestMapping("/api/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;
    private final GoodsCacheService goodsCacheService;

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

    /** 更新商品（r3-cache 缓存一致性的落点） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GoodsUpdateRequest request) {
        goodsService.update(id, request);
        return Result.ok();
    }

    /**
     * 【教学用】复现缓存一致性竞态（删缓存 + 并发回填旧值）。
     * 返回 stale=true 即代表缓存与 DB 不一致。
     */
    @PostMapping("/{id}/race")
    public Result<Map<String, Object>> race(@PathVariable Long id) throws InterruptedException {
        return Result.ok(goodsCacheService.simulateRace(id));
    }

    /** 【教学用】延迟双删修复后的对照。 */
    @PostMapping("/{id}/race-fixed")
    public Result<Map<String, Object>> raceFixed(@PathVariable Long id) throws InterruptedException {
        return Result.ok(goodsCacheService.simulateRaceFixed(id));
    }
}
