package com.lsy.seckill.modules.goods.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.modules.goods.dto.GoodsCreateRequest;
import com.lsy.seckill.modules.goods.dto.GoodsUpdateRequest;
import com.lsy.seckill.modules.goods.entity.Goods;

/**
 * 商品服务
 */
public interface GoodsService {

    /**
     * 分页查询商品
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param name     名称关键字，可为空
     */
    IPage<Goods> page(int pageNum, int pageSize, String name);

    /**
     * 查询商品详情
     *
     * @throws com.lsy.seckill.common.exception.BusinessException 商品不存在时抛出
     */
    Goods getById(Long id);

    /**
     * 新增商品
     *
     * @return 新商品 ID
     */
    Long create(GoodsCreateRequest request);

    /**
     * 更新商品
     *
     * <p>r3-cache 会在这个方法里加缓存删除（缓存一致性）。
     *
     * @throws com.lsy.seckill.common.exception.BusinessException 商品不存在时抛出
     */
    void update(Long id, GoodsUpdateRequest request);
}
