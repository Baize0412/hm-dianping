package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合
            return Result.fail("手机号错误");
        }
        // 生成保存验证码到redis
        String code = RandomUtil.randomNumbers(6);
//       session.setAttribute("code", code);
        //set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.info("code:{}", code);
        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFormDTO, HttpSession session) {
        // 校验
        String phone = loginFormDTO.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合
            return Result.fail("手机号错误");
        }
        //符合
        //校验验证码
        String code = loginFormDTO.getCode();
        //redis获取验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //判断是否为空或不一致
        if (cacheCode == null || !cacheCode.equals(code)) {
            //不一致
            return Result.fail("验证码不一致");
        }
        // 根据phone查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if (user == null) {
            // 创建用户
            user = createUserWithPhone(phone);
        }
        //用户信息保存到redis hashmap user->map
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);    // objcopy
        // TODO obj->hashmap,此处有坑stringRedisTemplate只能存储string字段 userDTO{id:}类型：long
        Map<String, Object> userToMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //生成token
        String token = UUID.randomUUID().toString();
        //保存用户信息到redis key:token value:user(map)
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userToMap);
        //设置token有效期 ex 30
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //基于session不需要返回登录凭证
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        //登出

        return null;
    }

    @Override
    public Result me() {
        // 从ThreadLocal获取用户信息
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        //保存
        save(user);
        return user;


    }
}
