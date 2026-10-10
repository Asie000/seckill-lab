-- ============================================================
-- Phase R 读侧造数：用户 / 商品 / 订单 / 明细 / 物流
-- 用途：为「订单列表 N+1」场景准备足量数据
-- 特性：幂等（可反复执行）；使用递归 CTE 批量生成，避免应用层循环插入
-- 规模：5000 用户 / 20 商品 / 2000 订单（每单 1 明细 + 1 物流）
-- ============================================================
USE `seckill`;
-- 递归 CTE 默认深度 1000，生成 5000 行前必须放宽（见设计文档 §9 R1）
SET SESSION cte_max_recursion_depth = 100001;

-- 清空（无外键，可安全 TRUNCATE）
TRUNCATE TABLE `order_item`;
TRUNCATE TABLE `order_logistics`;
TRUNCATE TABLE `orders`;
TRUNCATE TABLE `user`;
DELETE FROM `goods` WHERE `id` > 1;

-- 1) 5000 个用户
INSERT INTO `user` (`id`, `username`, `nickname`)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000)
SELECT n, CONCAT('u', n), CONCAT('用户', n) FROM seq;

-- 2) 20 个商品（id 2..21）
INSERT INTO `goods` (`id`, `name`, `price`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 2 UNION ALL SELECT n + 1 FROM seq WHERE n < 21)
SELECT n, CONCAT('商品-', n), 10.00 + n, 1 FROM seq;

-- 3) 2000 个订单（order_type=0 普通单）
INSERT INTO `orders` (`id`, `order_no`, `user_id`, `order_type`, `total_amount`, `status`, `create_time`)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT n, CONCAT('NO', LPAD(n, 10, '0')), (n % 5000) + 1, 0, 100.00 + n, 1, NOW() - INTERVAL n SECOND
FROM seq;

-- 4) 每单 1 条明细
INSERT INTO `order_item` (`order_id`, `goods_id`, `qty`, `price`)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT n, (n % 20) + 2, 1, 50.00 FROM seq;

-- 5) 每单 1 条物流
INSERT INTO `order_logistics` (`order_id`, `company`, `tracking_no`, `status`)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT n, '顺丰', CONCAT('SF', LPAD(n, 10, '0')), n % 4 FROM seq;

-- 6) 结果核对
SELECT 'user' AS tbl, COUNT(*) AS cnt FROM `user`
UNION ALL SELECT 'goods', COUNT(*) FROM `goods`
UNION ALL SELECT 'orders', COUNT(*) FROM `orders`
UNION ALL SELECT 'order_item', COUNT(*) FROM `order_item`
UNION ALL SELECT 'order_logistics', COUNT(*) FROM `order_logistics`;
