-- ============================================================
-- 压测前一键重置脚本：db/reset-stock.sql
-- 作用：每次切换并发梯度（如 100 -> 200 -> 500 -> 1000）之前执行
-- 耗时：0.01 秒完成重置，让数据库瞬间恢复纯净初始状态
-- ============================================================

USE `seckill`;

-- 1. 重置库存为 100（如果需要测持续吞吐，可手动改成 50000）
UPDATE `seckill_stock` SET `stock` = 100 WHERE `activity_id` = 1;

-- 2. 同步重置活动总库存基准
UPDATE `seckill_activity` SET `total_stock` = 100 WHERE `id` = 1;

-- 3. 闪电清空上一轮压测生成的订单与流水
TRUNCATE TABLE `seckill_order`;
TRUNCATE TABLE `stock_log`;
