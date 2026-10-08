-- ============================================================
-- seckill-lab 秒杀实验核心数据库初始化脚本
-- 特点：读写分离、库存窄表独立、严格防超卖设计
-- 说明：仅建表与初始化 1 条商品/活动/100 件库存，不含垃圾数据
-- ============================================================

CREATE DATABASE IF NOT EXISTS `seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `seckill`;

-- ------------------------------------------------------------
-- 1. 用户表（压测身份源，可选）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
  `username`    VARCHAR(64)     NOT NULL                COMMENT '登录名',
  `nickname`    VARCHAR(64)              DEFAULT NULL   COMMENT '昵称',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 商品主表（纯基础数据，彻底剔除 stock 字段，避免行锁宽表竞争）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `goods`;
CREATE TABLE `goods` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键',
  `name`        VARCHAR(128)    NOT NULL                COMMENT '商品名称',
  `price`       DECIMAL(10, 2)  NOT NULL                COMMENT '原价（元）',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '1-上架 0-下架',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主表';

-- ------------------------------------------------------------
-- 3. 秒杀活动表（读多写少，存不可变 total_stock 作为对账基准）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_activity`;
CREATE TABLE `seckill_activity` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '活动主键',
  `goods_id`      BIGINT UNSIGNED NOT NULL                COMMENT '商品 ID',
  `activity_name` VARCHAR(128)    NOT NULL                COMMENT '活动名称',
  `seckill_price` DECIMAL(10, 2)  NOT NULL                COMMENT '秒杀特价（元）',
  `total_stock`   INT             NOT NULL                COMMENT '★活动总库存（不可变，对账金标准）',
  `start_time`    DATETIME        NOT NULL                COMMENT '开始时间',
  `end_time`      DATETIME        NOT NULL                COMMENT '结束时间',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '1-启用 0-禁用',
  `version`       INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_start_end_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- ------------------------------------------------------------
-- 4. 秒杀库存表（★核心瓶颈：极窄表，行锁竞争现场）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_stock`;
CREATE TABLE `seckill_stock` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `activity_id` BIGINT UNSIGNED NOT NULL                COMMENT '活动 ID',
  `stock`       INT             NOT NULL                COMMENT '★剩余库存（写热点字段）',
  `version`     INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀库存表';

-- ------------------------------------------------------------
-- 5. 秒杀订单表（状态机流转与防重屏障）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单主键',
  `order_no`    VARCHAR(64)     NOT NULL                COMMENT '唯一订单号',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
  `activity_id` BIGINT UNSIGNED NOT NULL                COMMENT '活动 ID',
  `goods_id`    BIGINT UNSIGNED NOT NULL                COMMENT '商品 ID（冗余便于查询）',
  `amount`      DECIMAL(10, 2)  NOT NULL                COMMENT '实付金额',
  `status`      TINYINT         NOT NULL DEFAULT 0      COMMENT '0-待支付 1-已支付 2-已取消 3-超时关闭',
  `pay_time`    DATETIME                 DEFAULT NULL   COMMENT '支付时间',
  `version`     INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_activity` (`user_id`, `activity_id`),   -- V0 基线为普通索引（暴露重复下单）；V1 升级为 UNIQUE 唯一索引
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- ------------------------------------------------------------
-- 6. 库存变动流水表（异步对账、超时回补的核心证据链）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `stock_log`;
CREATE TABLE `stock_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水主键',
  `activity_id`  BIGINT UNSIGNED NOT NULL                COMMENT '活动 ID',
  `user_id`      BIGINT UNSIGNED          DEFAULT NULL   COMMENT '用户 ID',
  `order_no`     VARCHAR(64)              DEFAULT NULL   COMMENT '关联订单号',
  `change_type`  TINYINT         NOT NULL                COMMENT '变动类型：-1扣减，+1超时/取消回补',
  `quantity`     INT             NOT NULL                COMMENT '变动数量（恒为正数）',
  `stock_before` INT                      DEFAULT NULL   COMMENT '变动前剩余库存',
  `stock_after`  INT                      DEFAULT NULL   COMMENT '变动后剩余库存',
  `remark`       VARCHAR(255)             DEFAULT NULL   COMMENT '备注',
  `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_activity_id` (`activity_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存变动流水表';

-- ============================================================
-- 基础示例数据：仅需 1 条商品 + 1 条活动 + 100 件初始库存
-- ============================================================
INSERT INTO `goods` (`id`, `name`, `price`, `status`) VALUES
  (1, 'iPhone 17 Pro 秒杀款', 7999.00, 1);

INSERT INTO `seckill_activity`
  (`id`, `goods_id`, `activity_name`, `seckill_price`, `total_stock`, `start_time`, `end_time`, `status`)
VALUES
  (1, 1, 'iPhone 17 Pro 特惠秒杀', 3999.00, 100, '2026-01-01 00:00:00', '2030-12-31 23:59:59', 1);

INSERT INTO `seckill_stock` (`activity_id`, `stock`) VALUES (1, 100);
