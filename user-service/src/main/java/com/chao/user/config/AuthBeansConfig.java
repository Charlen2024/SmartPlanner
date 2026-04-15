package com.chao.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.chao.user.entity.AppUser;
import com.chao.user.service.AppUserService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

@Configuration
public class AuthBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            AppUserService appUserService,
            @Value("${auth.demo.username:demo}") String username,
            @Value("${auth.demo.password:demo123}") String password,
            @Value("${auth.demo.roles:USER}") String rolesCsv) {
        List<String> roles = List.of(rolesCsv.split(","));
        return (String inputUsername) -> {
            AppUser u = appUserService.findByUsername(inputUsername);
            if (u == null) {
                if (username.equals(inputUsername)) {
                    return User.builder()
                            .username(username)
                            .password(passwordEncoder.encode(password))
                            .authorities(AuthorityUtils.createAuthorityList(roles.toArray(new String[0])))
                            .build();
                }
                throw new UsernameNotFoundException("user not found");
            }
            UserDetails ud = User.builder()
                    .username(u.getUsername())
                    .password(u.getPasswordHash())
                    .authorities(AuthorityUtils.createAuthorityList(roles.toArray(new String[0])))
                    .build();
            return ud;
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
