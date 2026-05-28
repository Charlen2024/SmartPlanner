package com.chao.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.user.entity.AppUser;
import com.chao.user.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
@RequiredArgsConstructor
public class AppUserService {
    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AppUser register(String username, String rawPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        AppUser existed = findByUsername(username);
        if (existed != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AppUser created = new AppUser();
        created.setUsername(username.trim());
        created.setPasswordHash(passwordEncoder.encode(rawPassword));
        created.setScheduleImported(false);
        appUserMapper.insert(created);
        return created;
    }

    public AppUser findByUsername(String username) {
        if (username == null) {
            return null;
        }
        return appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username.trim()));
    }

    public AppUser getById(Long userId) {
        if (userId == null) {
            return null;
        }
        return appUserMapper.selectById(userId);
    }

    public Long getUserIdOrThrow(String username) {
        AppUser user = findByUsername(username);
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user.getId();
    }

    @Transactional
    public void markScheduleImported(Long userId) {
        AppUser user = getById(userId);
        if (user == null) {
            return;
        }
        user.setScheduleImported(true);
        appUserMapper.updateById(user);
    }

    @Transactional
    public void saveFirstWeekMonday(Long userId, java.time.LocalDate firstWeekMonday) {
        AppUser user = getById(userId);
        if (user == null) {
            return;
        }
        user.setFirstWeekMonday(firstWeekMonday);
        appUserMapper.updateById(user);
    }

    @Transactional
    public void clearFirstWeekMonday(Long userId) {
        AppUser user = getById(userId);
        if (user == null) {
            return;
        }
        user.setFirstWeekMonday(null);
        appUserMapper.updateById(user);
    }
}
