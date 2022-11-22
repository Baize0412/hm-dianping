package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.injector.methods.SelectById;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 在redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //不存在，在数据库查询根据ID查数据库
            Shop shop = getById(id);
            //判断是否命中的是否空值
            if (Objects.equals(shopJson, "")) {
                return Result.fail("店铺信息不存在");
            }
            //判断是否存在数据库
            if (shop == null) {
                //不存在返回404
                Result.fail("404");
                // 缓存穿透在redis中写入空值
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            }
            //存在 将店铺信息写回redis返回信息
            //obj -> json
            String shopToJson = JSONUtil.toJsonStr(shop);
            //防止所有key在同一时间失效所以给TTL添加随机值
            long randomTTL = RandomUtil.randomLong(1L, 30L);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopToJson,CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        }
        //redis中存在直接返回
        // json -> obj
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return Result.ok(shop);
    }

    @Override
    @Transactional //事务控制
    public Result saveShop(Shop shop) {
        //判断shop是否为空
        if (shop == null) {
            return Result.fail("店铺信息不能为空！");
        }
        //更新数据库
        save(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        //返回ok
        return Result.ok("添加成功");
    }
}
