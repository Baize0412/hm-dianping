package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {

    //注入stringRedis
    private final StringRedisTemplate stringRedisTemplate;    //不可以使用Spring自动装配否则拦截失败

    //构造器
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 获取token(从请求头获取)
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
        // 通过session获取用户信息
        // 通过token在redis中获取用户信息
        Map<Object, Object> userData = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

//        Object user = session.getAttribute("user");
        // 判断是否为空
        if (userData.isEmpty()) {
            // 是 拦截登录 返回错误代码401
            response.setStatus(401);
            return false;
        }
        // 否 放行
        // map -> obj
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userData, new UserDTO(), false);
        // 保存到ThreadLocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
