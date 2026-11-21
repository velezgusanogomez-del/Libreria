package com.librero.config;

import com.librero.controlador.LibreroControlador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/** Al terminar de arrancar: muestra las direcciones de acceso y abre el navegador. */
@Component
public class AlIniciar {

    private static final Logger log = LoggerFactory.getLogger(AlIniciar.class);

    private final Environment entorno;
    private final boolean abrirNavegador;

    public AlIniciar(Environment entorno, @Value("${librero.abrir-navegador:true}") boolean abrirNavegador) {
        this.entorno = entorno;
        this.abrirNavegador = abrirNavegador;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void listo() {
        String puerto = entorno.getProperty("local.server.port", entorno.getProperty("server.port", "8080"));
        String local = "http://localhost:" + puerto;
        log.info("Librero listo. Abra: {}", local);
        log.info("Desde otra computadora de la misma red: http://{}:{}", LibreroControlador.ipLocal(), puerto);
        log.info("Datos guardados en: {}", entorno.getProperty("spring.datasource.url"));
        if (!abrirNavegador) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(local));
            }
        } catch (Exception e) {
            log.info("No se pudo abrir el navegador ({}); ábralo manualmente en {}", e.getMessage(), local);
        }
    }
}
