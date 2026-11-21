package com.librero.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloquea temporalmente una IP tras varios intentos fallidos de inicio de sesión,
 * para impedir que alguien de la red pruebe contraseñas por fuerza bruta.
 */
@Component
public class IntentosLogin {

    private static final Logger log = LoggerFactory.getLogger(IntentosLogin.class);
    static final int MAX_FALLOS = 5;
    static final Duration BLOQUEO = Duration.ofMinutes(5);

    private record Registro(int fallos, Instant bloqueadoHasta) {
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final Clock reloj = Clock.systemUTC();

    public boolean estaBloqueada(String ip) {
        Registro r = registros.get(ip);
        if (r == null || r.bloqueadoHasta() == null) {
            return false;
        }
        if (reloj.instant().isAfter(r.bloqueadoHasta())) {
            registros.remove(ip);
            return false;
        }
        return true;
    }

    public void registrarFallo(String ip) {
        if (ip == null) {
            return;
        }
        Registro r = registros.merge(ip, new Registro(1, null), (viejo, nuevo) -> new Registro(viejo.fallos() + 1, null));
        if (r.fallos() >= MAX_FALLOS) {
            registros.put(ip, new Registro(r.fallos(), reloj.instant().plus(BLOQUEO)));
            log.warn("Inicio de sesión bloqueado {} minutos para {} tras {} intentos fallidos.",
                    BLOQUEO.toMinutes(), ip, r.fallos());
        }
    }

    public void registrarExito(String ip) {
        if (ip != null) {
            registros.remove(ip);
        }
    }

    /** Corta los POST /login de una IP bloqueada antes de comprobar la contraseña. */
    static final class Filtro extends OncePerRequestFilter {
        private final IntentosLogin intentos;

        Filtro(IntentosLogin intentos) {
            this.intentos = intentos;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !("POST".equals(request.getMethod()) && "/login".equals(request.getRequestURI()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (intentos.estaBloqueada(request.getRemoteAddr())) {
                response.sendRedirect("/login.html?bloqueado");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
