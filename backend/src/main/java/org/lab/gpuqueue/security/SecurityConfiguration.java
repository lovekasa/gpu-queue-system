package org.lab.gpuqueue.security;

import jakarta.servlet.http.HttpServletResponse;
import org.lab.gpuqueue.account.Account;
import org.lab.gpuqueue.account.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    UserDetailsService userDetailsService(AccountRepository accounts) {
        return loginName -> accounts.findByLoginName(Account.normalizeLoginName(loginName))
                .map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("登录名或口令不正确。"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/me", "/api/gpus").hasAnyRole("ADMIN", "MEMBER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/members",
                                "/api/admin/allocations/*/verified-release").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/admin/allocations/pending-verification").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/requests",
                                "/api/allocations/*/start",
                                "/api/allocations/*/return").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/requests", "/api/requests/*",
                                "/api/allocations", "/api/allocations/*",
                                "/api/messages", "/api/messages/*").hasRole("MEMBER")
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .csrf(Customizer.withDefaults())
                .formLogin(login -> login
                        .loginPage("/")
                        .loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, exception) ->
                                response.setStatus(exception instanceof InternalAuthenticationServiceException
                                        ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                                        : HttpServletResponse.SC_UNAUTHORIZED))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(authentication == null
                                        ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }
}
