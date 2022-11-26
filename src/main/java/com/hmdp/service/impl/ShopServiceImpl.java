package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.injector.methods.SelectById;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        // 缓存穿透
        //互斥锁解决缓存击穿  不需要提前加载
//        Shop shop = queryWitchMutex(id);
        //逻辑过期解决缓存击穿 不需要提前加载
//        Shop shop = queryWitchLogicalExpire(id);
        // 通过缓存空值解决缓存穿透 需要提前加载
//        Shop shop = cacheClient.queryWitchPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);
        // 通过互斥锁解决缓存击穿 需要提前加载
        Shop shop = cacheClient.queryWitchLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);

    }

    /**
     * 互斥锁查询商铺
     *
     * @param id id
     * @return
     */
    public Shop queryWitchMutex(Long id) {
        // 在redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在
            // 实现缓存重建
            //获取互斥锁
            try {
                boolean isLock = tryLock(LOCK_SHOP_KEY + id);
                //判断是否获得锁
                if (!isLock) {
                    //否休眠一段时间在从redis中查找缓存
                    Thread.sleep(50);
                    return queryWitchMutex(id);    //递归
                }
                //是查询数据库
                Shop shop = getById(id);
                //判断是否存在数据库
                if (shop == null) {
                    //不存在返回404
                    Result.fail("404");
                    // 缓存穿透在redis中写入空值
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                }
                //存在 将店铺信息写回redis返回信息
                //obj -> json
                String shopToJson = JSONUtil.toJsonStr(shop);
                //防止所有key在同一时间失效所以给TTL添加随机值
                long randomTTL = RandomUtil.randomLong(1L, 30L);
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopToJson, CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
                // 释放互斥锁
                unLock(LOCK_SHOP_KEY + id);
                return shop;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        //redis中存在直接返回
        // json -> obj
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期
     */
    public Shop queryWitchLogicalExpire(Long id) throws InterruptedException {
        // 在redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // TODO 提前将热点key缓存到redis则返回null
//            return null;
            //不存在在数据库查询封装过期时间
            saveShopToRedis(id,10L);
            shopJson =  stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        }
        //判断缓存是否过期 json -> obj
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期返回店铺信息
//            Shop data = (Shop) redisData.getData();   //这种写法容易空指针
            JSONObject data = (JSONObject) redisData.getData();
            Shop shop = JSONUtil.toBean(data, Shop.class);
            return shop;
        }
        //  已过期进行缓存重建
        // 获取锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        //判断锁是否过期
        if (isLock) {
            //锁未过期，创建一个新线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        return shop;
    }


    /* public Shop queryWitchPassThrough(Long id) {
    // 在redis中查询
    String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //判断是否存在
    if (StrUtil.isBlank(shopJson)) {
        //不存在，在数据库查询根据ID查数据库
        Shop shop = getById(id);
        //判断是否命中的是否空值
        if (Objects.equals(shopJson, "")) {
            return null;
        }
        //判断是否存在数据库
        if (shop == null) {
            //不存在返回404
            Result.fail("404");
            // 缓存穿透在redis中写入空值
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        //存在 将店铺信息写回redis返回信息
        //obj -> json
        String shopToJson = JSONUtil.toJsonStr(shop);
        //防止所有key在同一时间失效所以给TTL添加随机值
        long randomTTL = RandomUtil.randomLong(1L, 30L);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopToJson, CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
        return null;
    }
    //redis中存在直接返回
    // json -> obj
    Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    return shop;
}
*/


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

    /**
     * 获取锁
     *
     * @param key key
     * @return
     */
    public boolean tryLock(String key) {
        Boolean state = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(state);
    }

    /**
     * 解除锁
     *
     * @param key key
     */
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 查询商铺存入redis（封装过期时间
     *
     * @param id            id
     * @param expireSeconds 增加的时间
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException{
        // 查询数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存redis  obj -> json
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisData));
    }
}
