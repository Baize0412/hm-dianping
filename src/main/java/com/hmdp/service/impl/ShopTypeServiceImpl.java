package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate  stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //在redis中查询(String)
        String shopTypeJson = stringRedisTemplate.opsForValue().get(SHOP_TYPE);
        //判断是否存在
        if(StrUtil.isBlank(shopTypeJson)) {
            //不存在在数据库中查询返回list
            List<ShopType> sort = query().orderByAsc("sort").list();
            if (sort == null) {
                //数据库中不存在返回404
                return Result.fail("404");
            }
            //list -> json
            String sortToJson = JSONUtil.toJsonStr(sort);
            //存入redis(String)
            stringRedisTemplate.opsForValue().set(SHOP_TYPE,sortToJson);
            //返回list
            return Result.ok(sort);
        }
        //redis中存在返回list
        //json -> list
        List<ShopType> shop = JSONUtil.toList(new JSONArray(shopTypeJson), ShopType.class);
        return Result.ok(shop);
    }
}
