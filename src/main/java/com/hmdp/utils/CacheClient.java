package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将java对象存储在redis中
     *
     * @param value java对象
     * @param key   key
     */
    public void Set(String key, Object value) {
        // obj -> json
        String JsonStr = JSONUtil.toJsonStr(value);
        // 存到redis
        stringRedisTemplate.opsForValue().set(key, JsonStr);

//        if (Objects.equals(stringRedisTemplate.opsForValue().get(key), JsonStr)) {
//            //相等 存储成功
//            return true;
//        }
//        return false;
    }

    /**
     * 将java对象存储在redis中，并设置过期时间
     *
     * @param key      key
     * @param value    java对象
     * @param time     key过期时间
     * @param timeUnit 时间单位
     */
    public void Set(String key, Object value, Long time, TimeUnit timeUnit) {
        // obj -> json
        String JsonStr = JSONUtil.toJsonStr(value);
        // 存到redis并设置过期时间
        stringRedisTemplate.opsForValue().set(key, JsonStr, time, timeUnit);
    }

    /**
     * 将java对象存储在redis中，并设置逻辑过期时间
     *
     * @param key      key
     * @param value    java对象
     * @param time     key过期时间
     * @param timeUnit 时间单位
     */
    public void setWitchLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //创建redisData对象
        RedisData redisData = new RedisData();
        //set数据
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //obj -> json
        String JsonStr = JSONUtil.toJsonStr(redisData);
        //存入redis
        stringRedisTemplate.opsForValue().set(key, JsonStr);
    }

    /**
     * 根据指定的key来查询缓存（key = KeyPrefix + id）缓存空值
     *
     * @param KeyPrefix  keyPrefix（Key的前缀）
     * @param id         id（类型）
     * @param type       返回的类型
     * @param dbFallback 数据库查询逻辑<id类型，返回类型>
     * @param time       时间
     * @param timeUnit   时间单位
     * @param <R>        返回的类型
     * @param <ID>       ID类型
     * @return <R>
     */
    public <R, ID> R queryWitchPassThrough
    (String KeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = KeyPrefix + id;
        // 在redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误值
            return null;
        }
        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 不存在，返回错误
        if (r == null) {
            // 在redis这种写入空值
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        this.Set(key, r, time, timeUnit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 根据指定的key来查询缓存（key = KeyPrefix + id）互斥锁
     *
     * @param KeyPrefix  keyPrefix（Key的前缀）
     * @param id         id（类型）
     * @param type       返回的类型
     * @param dbFallback 数据库查询逻辑<id类型，返回类型>
     * @param time       时间
     * @param timeUnit   时间单位
     * @param <R>        返回的类型
     * @param <ID>       ID类型
     * @return <R>
     */
    public <R, ID> R queryWitchLogicalExpire
    (String KeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = KeyPrefix + id;       // 在redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            return null;
            //不存在在数据库查询封装过期时间
        }
        //判断缓存是否过期 json -> obj
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        //  已过期进行缓存重建
        // 获取锁
        String lockeKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockeKey);
        //判断锁是否过期
        if (isLock) {
            //锁未过期，创建一个新线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWitchLogicalExpire(key, r1, time, timeUnit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        return r;
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

}

