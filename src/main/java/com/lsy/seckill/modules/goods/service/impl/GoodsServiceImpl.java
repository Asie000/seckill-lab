package com.lsy.seckill.modules.goods.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.modules.goods.cache.GoodsCacheService;
import com.lsy.seckill.modules.goods.dto.GoodsCreateRequest;
import com.lsy.seckill.modules.goods.dto.GoodsUpdateRequest;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import com.lsy.seckill.modules.goods.service.GoodsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商品服务实现
 */
@Service
@RequiredArgsConstructor
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;
    private final GoodsCacheService goodsCacheService;

    @Override
    public IPage<Goods> page(int pageNum, int pageSize, String name) {
        return goodsMapper.selectPageByName(new Page<>(pageNum, pageSize), name);
    }

    @Override
    public Goods getById(Long id) {
        // r3-cache：改走缓存（Cache-Aside）
        Goods goods = goodsCacheService.getById(id);
        if (goods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        return goods;
    }

    @Override
    public Long create(GoodsCreateRequest request) {
        Goods goods = new Goods();
        goods.setName(request.getName());
        goods.setPrice(request.getPrice());
        goods.setStatus(1);
        goodsMapper.insert(goods);
        return goods.getId();
    }

    @Override
    public void update(Long id, GoodsUpdateRequest request) {
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        goods.setName(request.getName());
        goods.setPrice(request.getPrice());
        if (request.getStatus() != null) {
            goods.setStatus(request.getStatus());
        }
        goodsMapper.updateById(goods);
        // r12：改完 DB 删缓存；删失败发 Kafka 补偿重试（原 r3 为 goodsCacheService.evict(id)）
        goodsCacheService.evictWithCompensation(id);
    }
}
