package com.librero;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seguridad y contrato HTTP de la API. */
@AutoConfigureMockMvc
class ApiSeguridadTest extends PruebaBase {

    private static final String LLEGADA = """
            {"isbn":"API-1","titulo":"Libro API","editorial":"Ed","cantidad":%s,"cajas":1,
             "fecha":"2026-11-20","precio_venta":"99.90"}""";

    @Autowired MockMvc mvc;

    @Test
    void apiSinSesionResponde401() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/llegada").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(LLEGADA.formatted(1))).andExpect(status().isUnauthorized());
    }

    @Test
    void paginaPrincipalSinSesionRedirigeAlLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html"));
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void loginCorrectoEIncorrecto() throws Exception {
        mvc.perform(formLogin("/login").user("admin").password("clave-de-prueba"))
                .andExpect(authenticated()).andExpect(redirectedUrl("/"));
        mvc.perform(formLogin("/login").user("admin").password("mala"))
                .andExpect(unauthenticated()).andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    @WithMockUser
    void postSinTokenCsrfSeRechaza() throws Exception {
        mvc.perform(post("/api/llegada").contentType(MediaType.APPLICATION_JSON).content(LLEGADA.formatted(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void llegadaConSesionYCsrf() throws Exception {
        mvc.perform(post("/api/llegada").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(LLEGADA.formatted(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.libro.stock_actual").value(4))
                .andExpect(jsonPath("$.libro.precio_venta").value(99.90));
        mvc.perform(get("/api/inventario/API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Libro API"))
                .andExpect(jsonPath("$.poco_stock").value(true));
        mvc.perform(get("/api/libros/API-1/ficha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ultima_entrada.cantidad").value(4));
    }

    @Test
    @WithMockUser
    void erroresDeValidacionDevuelven400ConMensaje() throws Exception {
        mvc.perform(post("/api/llegada").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(LLEGADA.formatted("1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error", containsString("cantidad")));
        mvc.perform(post("/api/salida").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("no es json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/salida").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"NOPE\",\"cantidad\":1,\"fecha\":\"2026-11-20\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("no está registrado")));
    }

    @Test
    @WithMockUser
    void cuerpoDemasiadoGrandeSeRechaza() throws Exception {
        String enorme = "{\"nota\":\"" + "x".repeat(70_000) + "\"}";
        mvc.perform(post("/api/llegada").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(enorme))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @WithMockUser
    void cabecerasDeSeguridadYCsv() throws Exception {
        mvc.perform(get("/api/exportar/inventario.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("inventario.csv")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void bloqueoTrasVariosIntentosFallidos() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(loginDesde("10.0.0.99", "mala")).andExpect(redirectedUrl("/login.html?error"));
        }
        mvc.perform(loginDesde("10.0.0.99", "clave-de-prueba"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login.html?bloqueado"));
        // Otra IP no queda afectada.
        mvc.perform(loginDesde("10.0.0.100", "clave-de-prueba")).andExpect(authenticated());
    }

    private static MockHttpServletRequestBuilder loginDesde(String ip, String password) {
        return post("/login").param("username", "admin").param("password", password).with(csrf())
                .with(r -> { r.setRemoteAddr(ip); return r; });
    }
}
