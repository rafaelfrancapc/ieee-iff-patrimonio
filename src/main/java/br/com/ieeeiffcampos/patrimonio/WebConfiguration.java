package br.com.ieeeiffcampos.patrimonio;

import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.*;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(DirectoryProperties.class)
public class WebConfiguration {
    @Bean DefaultSpringSecurityContextSource directoryContext(DirectoryProperties config) {
        var source = new DefaultSpringSecurityContextSource(List.of(config.url()), config.baseDn());
        if (!config.bindDn().isBlank()) {
            source.setUserDn(config.bindDn()); source.setPassword(config.bindPassword());
        }
        source.setBaseEnvironmentProperties(Map.of("com.sun.jndi.ldap.connect.timeout", "5000",
            "com.sun.jndi.ldap.read.timeout", "5000"));
        source.setPooled(false);
        return source;
    }
    @Bean LdapTemplate directoryLdap(DefaultSpringSecurityContextSource source) { return new LdapTemplate(source); }
    @Bean AuthenticationProvider directoryAuthentication(DefaultSpringSecurityContextSource source, DirectoryProperties config) {
        var authenticator = new BindAuthenticator(source);
        authenticator.setUserDnPatterns(new String[]{"uid={0},ou=people"});
        var groups = new DefaultLdapAuthoritiesPopulator(source, "ou=groups");
        groups.setGroupSearchFilter("(member={0})"); groups.setGroupRoleAttribute("cn");
        groups.setRolePrefix(""); groups.setConvertToUpperCase(false);
        var provider = new LdapAuthenticationProvider(authenticator, groups);
        provider.setAuthoritiesMapper(authorities -> mapRoles(authorities, config));
        return provider;
    }
    static Set<GrantedAuthority> mapRoles(Collection<? extends GrantedAuthority> groups, DirectoryProperties config) {
        Set<GrantedAuthority> roles = new HashSet<>();
        roles.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
        for (var group : groups) {
            if (group.getAuthority().equalsIgnoreCase(config.directorGroup())) roles.add(new SimpleGrantedAuthority("ROLE_DIRECTOR"));
            if (group.getAuthority().equalsIgnoreCase(config.adminGroup())) {
                roles.add(new SimpleGrantedAuthority("ROLE_ADMIN")); roles.add(new SimpleGrantedAuthority("ROLE_DIRECTOR"));
            }
        }
        return roles;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, AuthenticationProvider provider, DirectoryRoles roles) throws Exception {
        return http.authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/assets/**", "/error").permitAll()
                .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> {
                if (req.getRequestURI().substring(req.getContextPath().length()).startsWith("/api/")) {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"message\":\"Entre novamente para acessar o inventário.\"}");
                } else new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login").commence(req,res,error);
            }))
            .addFilterAfter(new DirectoryRoleRefreshFilter(roles), AnonymousAuthenticationFilter.class)
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")))
            .build();
    }
}
