-- ============================================================
-- 压测后自动化对账核验脚本：db/reconcile.sql
-- 作用：压测结束后执行，一键给出：是否超卖、是否重复下单、库存是否守恒
-- 说明：统一订单表 orders 后，秒杀单 = order_type=1
-- ============================================================

USE `seckill`;
SET @aid = 1; -- 目标活动 ID

-- ① 超卖检测（核心）：剩余库存不能为负，且 [总库存 - 剩余库存] 必须精确等于有效订单数
SELECT '① 超卖检测' AS check_item,
       a.total_stock                                         AS '总库存',
       s.stock                                               AS '剩余库存',
       (a.total_stock - s.stock)                             AS '系统扣减数',
       COUNT(o.id)                                           AS '实际订单数',
       CASE WHEN s.stock < 0
              OR COUNT(o.id) <> (a.total_stock - s.stock)
              OR COUNT(o.id) > a.total_stock
            THEN '❌ FAIL (发生超卖/数据不一致)'
            ELSE '✅ PASS (无超卖)'
       END                                                   AS '核验结论'
FROM seckill_activity a
JOIN seckill_stock s ON s.activity_id = a.id
LEFT JOIN orders o ON o.activity_id = a.id AND o.order_type = 1 AND o.status IN (0, 1)
WHERE a.id = @aid
GROUP BY a.id, a.total_stock, s.stock;

-- ② 一人一单检测：统计是否有同一个用户抢到了 2 单及以上
SELECT '② 一人一单' AS check_item,
       user_id       AS '违规用户ID',
       COUNT(*)      AS '下单总数',
       '❌ FAIL (存在刷单/重复下单)' AS '核验结论'
FROM orders
WHERE activity_id = @aid AND order_type = 1 AND status IN (0, 1)
GROUP BY user_id
HAVING COUNT(*) > 1;

-- ③ 库存守恒定律：流水净扣减数量 必须完全等于 (总库存 - 剩余库存)
SELECT '③ 库存守恒' AS check_item,
       COALESCE(SUM(CASE WHEN l.change_type = -1 THEN l.quantity ELSE -l.quantity END), 0) AS '流水净扣减数',
       (a.total_stock - s.stock)                                                            AS '库存扣减数',
       CASE WHEN COALESCE(SUM(CASE WHEN l.change_type = -1 THEN l.quantity ELSE -l.quantity END), 0)
                 = (a.total_stock - s.stock)
            THEN '✅ PASS (流水完全吻合)'
            ELSE '❌ FAIL (流水与库存脱节)'
       END                                                                                  AS '核验结论'
FROM seckill_activity a
JOIN seckill_stock s ON s.activity_id = a.id
LEFT JOIN stock_log l ON l.activity_id = a.id
WHERE a.id = @aid
GROUP BY a.id, a.total_stock, s.stock;
