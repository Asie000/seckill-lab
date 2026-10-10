-- ============================================================
-- 压测前一键重置脚本：db/reset-stock.sql
-- 作用：每次切换并发梯度（如 100 -> 200 -> 500 -> 1000）之前执行
-- 说明：统一订单表 orders 后，秒杀单用 order_type=1 区分，
--       重置时只清秒杀单，保留普通单（order_type=0）
-- ============================================================

USE `seckill`;

-- 1. 重置库存为 100
UPDATE `seckill_stock` SET `stock` = 100 WHERE `activity_id` = 1;

-- 2. 同步重置活动总库存基准
UPDATE `seckill_activity` SET `total_stock` = 100 WHERE `id` = 1;

-- 3. 清空上一轮压测生成的秒杀单及其明细、流水
DELETE oi FROM `order_item` oi
    JOIN `orders` o ON o.id = oi.order_id
    WHERE o.order_type = 1;

DELETE ol FROM `order_logistics` ol
    JOIN `orders` o ON o.id = ol.order_id
    WHERE o.order_type = 1;

DELETE FROM `orders` WHERE `order_type` = 1;

TRUNCATE TABLE `stock_log`;

-- 4. 核对
SELECT 'seckill_activity' AS tbl, total_stock AS cnt FROM `seckill_activity` WHERE id = 1
UNION ALL SELECT 'seckill_stock', stock FROM `seckill_stock` WHERE activity_id = 1
UNION ALL SELECT 'seckill_orders', COUNT(*) FROM `orders` WHERE order_type = 1;
