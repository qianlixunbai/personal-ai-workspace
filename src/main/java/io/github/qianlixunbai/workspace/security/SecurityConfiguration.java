package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.common.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, LocalClientToken token) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        return http.csrf(csrf -> csrf.disable()).cors(cors -> cors.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/health",
                                "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(401); response.setContentType("application/json");
                    response.getOutputStream().write(json.writeValueAsBytes(ApiError.of(ErrorCode.UNAUTHORIZED, "HTTP")));
                }))
                .addFilterBefore(new LocalClientFilter(token), UsernamePasswordAuthenticationFilter.class).build();
    }

    @Bean
    UserDetailsService noPasswordLogin() { return ignored -> { throw new UsernameNotFoundException("Disabled"); }; }
}
