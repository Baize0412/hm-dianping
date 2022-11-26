package com.hmdp.utils;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class RedisIdWorker {
    
    public long nextId(String keyPrefix) {
        //时间戳
        
        //序列号
        
        //拼接返回
        return 0L;
    }

    public static void main(String[] args) {
        LocalDateTime of = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long l = of.toEpochSecond(ZoneOffset.UTC);
        System.out.println(l);

    }
    
}
