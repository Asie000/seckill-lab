package com.lsy.seckill.modules.seckill.controller;

import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接口（Phase S 写侧主链路）
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /** 秒杀下单（userId 请求参数直传，无鉴权） */
    @PostMapping("/{activityId}")
    public Result<String> seckill(@PathVariable Long activityId, @RequestParam Long userId) {
        return Result.ok(seckillService.seckill(activityId, userId));
    }
}
