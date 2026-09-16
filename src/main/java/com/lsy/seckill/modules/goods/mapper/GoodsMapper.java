package com.lsy.seckill.modules.goods.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lsy.seckill.modules.goods.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 商品 Mapper
 *
 * <p>继承 {@link BaseMapper} 获得单表 CRUD；复杂 SQL 写在
 * src/main/resources/mapper/GoodsMapper.xml 中。
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    /**
     * 按名称分页查询（自定义 SQL 示例）
     *
     * @param page 分页参数
     * @param name 商品名称关键字，可为空
     */
    IPage<Goods> selectPageByName(Page<Goods> page, @Param("name") String name);

    /**
     * 数据库原子扣减库存（stock >= quantity 才会扣减成功）
     *
     * @return 受影响行数，0 表示库存不足
     */
    int deductStock(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);
}
