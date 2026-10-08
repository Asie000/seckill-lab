-- ============================================================
-- 批量生成压测用户脚本：db/seed-users.sql
-- 作用：利用 MySQL 8 递归 CTE 一次性生成 10 万个测试用户（备用）
-- 耗时：约 1~2 秒
-- ============================================================

USE `seckill`;

-- 临时放开递归深度限制（默认 1000，生成 10 万条需要放开）
SET SESSION cte_max_recursion_depth = 100001;

INSERT INTO `user` (`username`, `nickname`)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 100000
)
SELECT CONCAT('user', LPAD(n, 6, '0')), CONCAT('压测用户', n)
FROM seq;
