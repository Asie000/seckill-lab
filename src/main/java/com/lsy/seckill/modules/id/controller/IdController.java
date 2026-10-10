package com.lsy.seckill.modules.id.controller;

import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.id.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 分布式 ID 接口 —— ★ r8-redis-idworker
 */
@RestController
@RequestMapping("/api/id")
@RequiredArgsConstructor
public class IdController {

    private final RedisIdWorker redisIdWorker;

    /** 生成一个 ID */
    @GetMapping("/next")
    public Result<Long> next(@RequestParam(defaultValue = "order") String prefix) {
        return Result.ok(redisIdWorker.nextId(prefix));
    }

    /**
     * 并发自检：多线程同时生成大量 ID，校验唯一性，并与 UUID 对比。
     *
     * @param strategy redis | uuid
     */
    @PostMapping("/selftest")
    public Result<Map<String, Object>> selftest(@RequestParam(defaultValue = "200") int threads,
                                                @RequestParam(defaultValue = "500") int perThread,
                                                @RequestParam(defaultValue = "redis") String strategy)
            throws InterruptedException {
        int total = threads * perThread;
        Set<Long> idSet = ConcurrentHashMap.newKeySet();
        Set<String> uuidSet = ConcurrentHashMap.newKeySet();
        List<Long> sample = new ArrayList<>();
        Object sampleLock = new Object();

        CountDownLatch latch = new CountDownLatch(threads);
        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        if ("uuid".equalsIgnoreCase(strategy)) {
                            uuidSet.add(UUID.randomUUID().toString());
                        } else {
                            long id = redisIdWorker.nextId("selftest");
                            idSet.add(id);
                            if (sample.size() < 5) {
                                synchronized (sampleLock) {
                                    if (sample.size() < 5) {
                                        sample.add(id);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
            thread.start();
        }
        latch.await();
        long millis = System.currentTimeMillis() - start;

        int distinct = "uuid".equalsIgnoreCase(strategy) ? uuidSet.size() : idSet.size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategy", strategy);
        data.put("threads", threads);
        data.put("perThread", perThread);
        data.put("total", total);
        data.put("distinct", distinct);
        data.put("duplicates", total - distinct);
        data.put("millis", millis);
        data.put("idsPerSec", millis == 0 ? total : (long) (total * 1000L / millis));
        data.put("sortable", !"uuid".equalsIgnoreCase(strategy));
        data.put("sample", sample);
        return Result.ok(data);
    }
}
