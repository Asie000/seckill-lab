package com.lsy.seckill.modules.goods.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.goods.cache.GoodsCacheService;
import com.lsy.seckill.modules.goods.cache.HotGoodsService;
import com.lsy.seckill.modules.goods.cache.MultiLevelGoodsCacheService;
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

import java.util.LinkedHashMap;
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
    private final HotGoodsService hotGoodsService;
    private final MultiLevelGoodsCacheService multiLevelGoodsCacheService;

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
        multiLevelGoodsCacheService.invalidate(id);
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

    /** r5-cache-breakdown：热 key 读取（策略由 seckill.cache.breakdown 决定） */
    @GetMapping("/{id}/hot")
    public Result<Goods> hot(@PathVariable Long id) {
        return Result.ok(hotGoodsService.get(id));
    }

    /** r5：清除热 key 缓存（压测前冷启动） */
    @PostMapping("/{id}/hot/evict")
    public Result<Void> hotEvict(@PathVariable Long id) {
        hotGoodsService.evict(id);
        return Result.ok();
    }

    /** r5：查看回源次数统计 */
    @GetMapping("/hot/stats")
    public Result<Map<String, Object>> hotStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", hotGoodsService.mode());
        data.put("dbLoads", hotGoodsService.dbLoads());
        return Result.ok(data);
    }

    /** r5：重置回源统计 */
    @PostMapping("/hot/stats/reset")
    public Result<Void> hotStatsReset() {
        hotGoodsService.resetStats();
        return Result.ok();
    }

    /** r6-multilevel-cache：多级缓存读取（Caffeine+Redis，开关 seckill.cache.multilevel.enabled） */
    @GetMapping("/{id}/ml")
    public Result<Goods> ml(@PathVariable Long id) {
        return Result.ok(multiLevelGoodsCacheService.get(id));
    }

    /** r6：清空多级缓存（压测前冷启动） */
    @PostMapping("/{id}/ml/evict")
    public Result<Void> mlEvict(@PathVariable Long id) {
        multiLevelGoodsCacheService.invalidate(id);
        return Result.ok();
    }

    /** r6：多级缓存命中统计 */
    @GetMapping("/ml/stats")
    public Result<Map<String, Object>> mlStats() {
        return Result.ok(multiLevelGoodsCacheService.stats());
    }

    /** r6：重置多级缓存统计 */
    @PostMapping("/ml/stats/reset")
    public Result<Void> mlStatsReset() {
        multiLevelGoodsCacheService.resetStats();
        return Result.ok();
    }

    /** 【教学用】r6b：复现多级缓存一致性竞态 */
    @PostMapping("/{id}/ml/race")
    public Result<Map<String, Object>> mlRace(@PathVariable Long id) throws InterruptedException {
        return Result.ok(multiLevelGoodsCacheService.simulateMlRace(id));
    }

    /** 【教学用】r6b：延迟失效修复后的对照 */
    @PostMapping("/{id}/ml/race-fixed")
    public Result<Map<String, Object>> mlRaceFixed(@PathVariable Long id) throws InterruptedException {
        return Result.ok(multiLevelGoodsCacheService.simulateMlRaceFixed(id));
    }
}
