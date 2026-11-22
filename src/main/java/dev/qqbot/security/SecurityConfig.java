/**
 * [INPUT]: 依赖管理员 BCrypt 配置与 Spring Security。
 * [OUTPUT]: 提供会话认证、CSRF、退出与内容安全策略。
 * [POS]: HTTP 安全边界；管理 API 不依赖前端隐藏操作来鉴权。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.security;

import dev.qqbot.BotProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService users(BotProperties props) {
        if (props.adminPasswordHash() == null
                || !props.adminPasswordHash().matches("\\$2[aby]\\$\\d{2}\\$.{53}")) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD_HASH must be a BCrypt hash. Run powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start.ps1 to set an administrator password and save its hash to .env; see README.md.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(props.adminUsername())
                        .password(props.adminPasswordHash())
                        .roles("ADMIN")
                        .build());
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/api/admin/csrf",
                                                "/api/admin/login",
                                                "/api/ingress/**",
                                                "/callbacks/**",
                                                "/",
                                                "/index.html",
                                                "/assets/**",
                                                "/favicon.ico",
                                                "/actuator/health")
                                        .permitAll()
                                        .anyRequest()
                                        .hasRole("ADMIN"))
                .csrf(
                        c ->
                                c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                                        .ignoringRequestMatchers(
                                                new AntPathRequestMatcher("/callbacks/**"),
                                                new AntPathRequestMatcher("/api/ingress/**")))
                .formLogin(
                        f ->
                                f.loginProcessingUrl("/api/admin/login")
                                        .successHandler(
                                                (r, s, a) -> {
                                                    s.setContentType("application/json");
                                                    s.getWriter().write("{\"authenticated\":true}");
                                                })
                                        .failureHandler((r, s, e) -> s.sendError(401)))
                .logout(
                        l ->
                                l.logoutUrl("/api/admin/logout")
                                        .logoutSuccessHandler((r, s, a) -> s.setStatus(204)))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        (r, s, x) ->
                                                s.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .headers(
                        h ->
                                h.contentSecurityPolicy(
                                        c ->
                                                c.policyDirectives(
                                                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")))
                .build();
    }
}
