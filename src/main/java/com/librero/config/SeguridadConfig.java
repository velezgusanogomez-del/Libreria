package com.librero.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Seguridad de Librero:
 * - Inicio de sesión con usuario y contraseña (formulario en /login.html, sesión con cookie).
 * - La API responde 401 sin sesión (la interfaz redirige al login).
 * - Protección CSRF: cookie XSRF-TOKEN que la interfaz devuelve en la cabecera X-XSRF-TOKEN.
 * - Cabeceras de seguridad: CSP, X-Frame-Options DENY, nosniff, Referrer-Policy.
 * - Límite de intentos de inicio de sesión fallidos y de tamaño de las peticiones.
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    private static final RequestMatcher ES_API = request -> request.getRequestURI().startsWith("/api/");

    private static final String CSP = "default-src 'self'; img-src 'self' data:; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filtros(HttpSecurity http, IntentosLogin intentosLogin,
                                       @Value("${librero.limite-cuerpo-bytes:65536}") long limiteCuerpo) throws Exception {
        CookieCsrfTokenRepository csrfRepositorio = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepositorio.setCookieCustomizer(c -> c.sameSite("Strict"));

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/login.js", "/estilos.css", "/img/**",
                                "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, autenticacion) -> {
                            intentosLogin.registrarExito(request.getRemoteAddr());
                            response.sendRedirect("/");
                        })
                        .failureHandler((request, response, error) -> {
                            if (error instanceof BadCredentialsException) {
                                intentosLogin.registrarFallo(request.getRemoteAddr());
                            }
                            response.sendRedirect("/login.html?error");
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html?salir")
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> {
                    // La API responde 401 (la interfaz redirige); las páginas van al formulario de login.
                    if (ES_API.matches(request)) {
                        response.sendError(HttpStatus.UNAUTHORIZED.value());
                    } else {
                        response.sendRedirect("/login.html");
                    }
                }))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepositorio)
                        // El token se envía tal cual (sin máscara XOR) porque lo lee el JavaScript de la cookie.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .addFilterBefore(new LimiteCuerpoFiltro(limiteCuerpo), CsrfFilter.class)
                .addFilterBefore(new IntentosLogin.Filtro(intentosLogin), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFiltro(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Spring Security genera el token CSRF de forma diferida; este filtro lo fuerza en cada
     * petición para que la cookie XSRF-TOKEN siempre exista (la necesitan login.js y app.js).
     */
    static final class CsrfCookieFiltro extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
