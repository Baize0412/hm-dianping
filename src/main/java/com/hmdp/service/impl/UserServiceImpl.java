package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合
            return Result.fail("手机号错误");
        }
        // 生成保存验证码到session
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code", code);
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
        //session获取验证码
        Object cacheCode = session.getAttribute("code");
        //判断是否为空或不一致
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            //不一致
            return Result.fail("验证码不一致");
        }
        //根据phone查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if (user == null) {
            // 创建用户
            user = createUserWithPhone(phone);
        }
        //用户信息保存到session
        session.setAttribute("user", user);
        //基于session不需要返回登录凭证
        return Result.ok();
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