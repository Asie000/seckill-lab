package com.lsy.seckill.modules.kafka.controller;

import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.kafka.KafkaLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * kafka-lab 演示接口（r10）
 */
@RestController
@RequestMapping("/api/kafka")
@RequiredArgsConstructor
public class KafkaLabController {

    private final KafkaLabService kafkaLabService;

    /** 生产 count 条消息；useKey=true 用逻辑键分区，否则打散 */
    @PostMapping("/produce")
    public Result<Map<String, Object>> produce(@RequestParam(defaultValue = "A") String key,
                                               @RequestParam(defaultValue = "1000") int count,
                                               @RequestParam(defaultValue = "true") boolean useKey) {
        kafkaLabService.produce(key, count, useKey);
        return Result.ok(Map.of("produced", count, "key", key, "useKey", useKey));
    }

    /** 设置消费者每条消息的处理耗时（制造堆积） */
    @PostMapping("/slow")
    public Result<Map<String, Object>> slow(@RequestParam long ms) {
        kafkaLabService.setSlowMs(ms);
        return Result.ok(Map.of("slowMs", ms));
    }

    /** 应用内计数（生产/消费/乱序） */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(kafkaLabService.stats());
    }

    /** 消费者组真实 lag（AdminClient） */
    @GetMapping("/lag")
    public Result<Map<String, Object>> lag() throws Exception {
        return Result.ok(kafkaLabService.lag());
    }

    /** 重置计数与消费者偏移无关 */
    @PostMapping("/reset")
    public Result<Void> reset() {
        kafkaLabService.reset();
        return Result.ok();
    }
}
