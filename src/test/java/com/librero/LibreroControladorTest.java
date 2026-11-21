package com.librero;

import com.librero.controlador.LibreroControlador;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;
import com.librero.servicio.LibreroServicer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de LibreroControlador (token de acceso, G5).
 */
public class LibreroControladorTest {

    private LibreroServicer crearServicio() throws Exception {
        File db = File.createTempFile("librero-token-test-", ".db");
        db.deleteOnExit();
        Connection conexion = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
        LibreroApplication.aplicarSql(conexion, Files.readString(Paths.get("base-de-datos/esquema.sql"), StandardCharsets.UTF_8));
        LibreroApplication.migrarColumnas(conexion);
        return new LibreroServicer(conexion, new LibroRepositorio(conexion), new MovimientoRepositorio(conexion), 5);
    }

    @Test
    void testTokenSinConfigurar() throws Exception {
        // Sin LIBRERO_TOKEN: no se exige token (comportamiento anterior).
        LibreroControlador controlador = new LibreroControlador(crearServicio(), 8080, null);
        assertTrue(controlador.esTokenValido(null));
        assertTrue(controlador.esTokenValido("cualquier-token"));
    }

    @Test
    void testTokenConfigurado() throws Exception {
        // Con LIBRERO_TOKEN configurado: se exige el token en todos los endpoints /api/.
        LibreroControlador controlador = new LibreroControlador(crearServicio(), 8080, "secret123");
        assertTrue(controlador.esTokenValido("secret123"));
        assertFalse(controlador.esTokenValido("token-incorrecto"));
        assertFalse(controlador.esTokenValido(null));
    }
}
