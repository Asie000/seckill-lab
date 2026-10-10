package com.lsy.seckill.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper（仅用于订单列表补查用户信息）
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
