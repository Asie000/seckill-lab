package com.lsy.seckill.modules.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.order.dto.OrderCreateRequest;
import com.lsy.seckill.modules.order.service.OrderService;
import com.lsy.seckill.modules.order.vo.OrderVO;
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
 * 订单接口（Phase R 读侧：后台订单管理）
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 订单列表
     *
     * <p>userId 可选：为空时返回全部订单（后台视角），压测用这个更能触发 N+1。
     */
    @GetMapping("/list")
    public Result<IPage<OrderVO>> list(@RequestParam(required = false) Long userId,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(orderService.list(userId, pageNum, pageSize));
    }

    /** 订单详情 */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.detail(orderNo));
    }

    /** 普通下单 */
    @PostMapping("/create")
    public Result<String> create(@Valid @RequestBody OrderCreateRequest request) {
        return Result.ok(orderService.create(request));
    }
}
