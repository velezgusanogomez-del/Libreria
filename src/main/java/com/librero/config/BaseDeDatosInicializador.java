package com.librero.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * Crea el esquema, migra bases de datos de versiones anteriores y carga los datos de
 * ejemplo una única vez. Se ejecuta al crear el contexto, antes de que el servidor
 * empiece a aceptar peticiones.
 */
@Component
public class BaseDeDatosInicializador implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BaseDeDatosInicializador.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public BaseDeDatosInicializador(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        new ResourceDatabasePopulator(new ClassPathResource("db/esquema.sql")).execute(dataSource);
        migrar();
        if (!"1".equals(leerMetadata("seed_aplicado").orElse(null))) {
            new ResourceDatabasePopulator(new ClassPathResource("db/datos-ejemplo.sql")).execute(dataSource);
            guardarMetadata("seed_aplicado", "1");
            log.info("Datos de ejemplo cargados.");
        }
    }

    /** Migraciones idempotentes para bases creadas con versiones anteriores de Librero. */
    private void migrar() {
        if (!tieneColumna("activo")) {
            log.info("Migración: añadiendo columna 'activo' a libros.");
            jdbc.execute("ALTER TABLE libros ADD COLUMN activo INTEGER NOT NULL DEFAULT 1");
        }
        if (!tieneColumna("vendidos_acumulados")) {
            log.info("Migración: añadiendo columna 'vendidos_acumulados' a libros.");
            jdbc.execute("ALTER TABLE libros ADD COLUMN vendidos_acumulados INTEGER NOT NULL DEFAULT 0");
        }
        if (!tieneColumna("precio_venta_centavos")) {
            log.info("Migración: añadiendo columna 'precio_venta_centavos' a libros.");
            jdbc.execute("ALTER TABLE libros ADD COLUMN precio_venta_centavos INTEGER");
        }
        if (tieneColumna("precio_venta")) {
            // La versión 1 guardaba el precio como REAL (decimal binario): pasamos a centavos.
            log.info("Migración: convirtiendo 'precio_venta' (REAL) a centavos.");
            jdbc.execute("UPDATE libros SET precio_venta_centavos = CAST(ROUND(precio_venta * 100) AS INTEGER) "
                    + "WHERE precio_venta IS NOT NULL AND precio_venta_centavos IS NULL");
            jdbc.execute("ALTER TABLE libros DROP COLUMN precio_venta");
        }
        // El ISBN ya es PRIMARY KEY: este índice era redundante.
        jdbc.execute("DROP INDEX IF EXISTS idx_libros_isbn");
    }

    private boolean tieneColumna(String columna) {
        List<String> columnas = jdbc.query("PRAGMA table_info(libros)", (rs, i) -> rs.getString("name"));
        return columnas.contains(columna);
    }

    public Optional<String> leerMetadata(String clave) {
        return jdbc.query("SELECT value FROM metadata WHERE key = ?", (rs, i) -> rs.getString(1), clave)
                .stream().findFirst();
    }

    public void guardarMetadata(String clave, String valor) {
        jdbc.update("INSERT INTO metadata(key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", clave, valor);
    }
}
