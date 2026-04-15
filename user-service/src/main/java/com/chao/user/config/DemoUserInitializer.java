package com.chao.user.config;

import com.chao.user.entity.AppUser;
import com.chao.user.service.AppUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DemoUserInitializer implements ApplicationRunner {
    private final AppUserService appUserService;

    @Value("${auth.demo.username:demo}")
    private String demoUsername;

    @Value("${auth.demo.password:demo123}")
    private String demoPassword;

    @Override
    public void run(ApplicationArguments args) {
        AppUser existed = appUserService.findByUsername(demoUsername);
        if (existed != null) {
            return;
        }
        appUserService.register(demoUsername, demoPassword);
        appUserService.markScheduleImported(appUserService.getUserIdOrThrow(demoUsername));
    }
}
