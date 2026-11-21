package com.librero;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Punto de entrada de Librero (Inventario de libros) sobre Spring Boot.
 *
 * Variables de entorno opcionales:
 * - LIBRERO_PORT: puerto HTTP (8080).
 * - LIBRERO_DATOS: carpeta donde vive librero.db (./base-de-datos).
 * - LIBRERO_USUARIO / LIBRERO_PASSWORD: credenciales de acceso.
 * - LIBRERO_ABRIR_NAVEGADOR: false para no abrir el navegador al iniciar.
 */
@SpringBootApplication
public class LibreroApplication {

    public static void main(String[] args) throws IOException {
        // SQLite crea el archivo pero no la carpeta: la creamos antes de abrir la conexión.
        String carpeta = System.getenv().getOrDefault("LIBRERO_DATOS", "base-de-datos");
        Files.createDirectories(Path.of(carpeta));

        SpringApplication app = new SpringApplication(LibreroApplication.class);
        // Necesario para poder abrir el navegador con java.awt.Desktop.
        app.setHeadless(false);
        app.run(args);
    }
}
