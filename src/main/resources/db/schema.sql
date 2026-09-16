-- ============================================================
-- 秒杀实验库初始化脚本
-- 说明：docker compose 首次启动 MySQL 容器时会自动执行本脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS `seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `seckill`;

-- ------------------------------------------------------------
-- 商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `goods`;
CREATE TABLE `goods`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(128)    NOT NULL COMMENT '商品名称',
    `price`       DECIMAL(10, 2)  NOT NULL COMMENT '价格（元）',
    `stock`       INT             NOT NULL DEFAULT 0 COMMENT '库存',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1-上架 0-下架',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品';

INSERT INTO `goods` (`name`, `price`, `stock`, `status`)
VALUES ('秒杀示例商品 A', 99.00, 100, 1),
       ('秒杀示例商品 B', 199.00, 50, 1);

-- ------------------------------------------------------------
-- 秒杀活动商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_goods`;
CREATE TABLE `seckill_goods`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `goods_id`      BIGINT UNSIGNED NOT NULL COMMENT '商品 ID',
    `seckill_price` DECIMAL(10, 2)  NOT NULL COMMENT '秒杀价（元）',
    `stock`         INT             NOT NULL COMMENT '秒杀库存',
    `start_time`    DATETIME        NOT NULL COMMENT '开始时间',
    `end_time`      DATETIME        NOT NULL COMMENT '结束时间',
    `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_goods_id` (`goods_id`),
    KEY `idx_start_end_time` (`start_time`, `end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀活动商品';

-- ------------------------------------------------------------
-- 秒杀订单表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`        VARCHAR(64)     NOT NULL COMMENT '订单号',
    `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `goods_id`        BIGINT UNSIGNED NOT NULL COMMENT '商品 ID',
    `seckill_goods_id` BIGINT UNSIGNED NOT NULL COMMENT '秒杀活动商品 ID',
    `amount`          DECIMAL(10, 2)  NOT NULL COMMENT '支付金额（元）',
    `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_seckill` (`user_id`, `seckill_goods_id`) COMMENT '同一用户同一活动只能下一单，防重复下单'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀订单';
