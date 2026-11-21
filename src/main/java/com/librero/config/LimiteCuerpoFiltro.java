package com.librero.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Rechaza peticiones con cuerpo demasiado grande (413) o sin longitud declarada (411),
 * para que nadie pueda agotar la memoria del servidor enviando un cuerpo enorme.
 */
public class LimiteCuerpoFiltro extends OncePerRequestFilter {

    private final long limite;

    public LimiteCuerpoFiltro(long limite) {
        this.limite = limite;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long largo = request.getContentLengthLong();
        if (largo > limite) {
            responder(response, 413, "La petición es demasiado grande.");
            return;
        }
        if (largo < 0 && request.getHeader("Transfer-Encoding") != null) {
            responder(response, 411, "La petición debe indicar Content-Length.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void responder(HttpServletResponse response, int estado, String mensaje) throws IOException {
        response.setStatus(estado);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"ok\":false,\"error\":\"" + mensaje + "\"}");
    }
}
