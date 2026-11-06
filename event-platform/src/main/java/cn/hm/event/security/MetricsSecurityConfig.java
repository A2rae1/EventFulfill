package cn.hm.event.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Scraper credentials are accepted only on this metrics path, never on business APIs. */
@Configuration
public class MetricsSecurityConfig {
    @Bean @Order(0)
    SecurityFilterChain metricSecurity(HttpSecurity http,PasswordEncoder encoder,@Value("${METRICS_PASSWORD:}")String configured)throws Exception{
        String secret=configured.length()>=32?configured:UUID.randomUUID().toString();
        var users=new InMemoryUserDetailsManager(User.withUsername("metrics").password(encoder.encode(secret)).roles("METRICS").build());
        var provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/actuator/prometheus").csrf(c->c.disable()).authenticationManager(new ProviderManager(provider))
            .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().hasRole("METRICS")).httpBasic(Customizer.withDefaults()).build();
    }
}
