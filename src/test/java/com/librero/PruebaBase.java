package com.librero;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Contexto Spring con una base SQLite temporal; cada prueba empieza con las tablas vacías. */
@SpringBootTest(properties = {
        "librero.abrir-navegador=false",
        "librero.seguridad.usuario=admin",
        "librero.seguridad.password=clave-de-prueba"
})
public abstract class PruebaBase {

    private static final Path CARPETA = crearCarpeta();

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void baseTemporal(DynamicPropertyRegistry registro) {
        registro.add("librero.directorio-datos", CARPETA::toString);
    }

    @BeforeEach
    void vaciarTablas() {
        jdbc.update("DELETE FROM movimientos");
        jdbc.update("DELETE FROM libros");
    }

    private static Path crearCarpeta() {
        try {
            Path carpeta = Files.createTempDirectory("librero-test-");
            carpeta.toFile().deleteOnExit();
            return carpeta;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
