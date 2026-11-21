package com.librero;

import com.librero.controlador.LibreroControlador;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;
import com.librero.servicio.LibreroServicer;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Punto de entrada de Librero (Inventario de libros).
 *
 * - Carga application.properties y resuelve el puerto (System.getProperty("server.port"),
 *   variable de entorno LIBRERO_PORT o properties, puerto 8080).
 * - Lee el token desde la variable de entorno LIBRERO_TOKEN (G5).
 * - Abre la base de datos real (SQLite: base-de-datos/librero.db).
 * - Aplica el esquema (DDL) y las migraciones (G6).
 * - Aplica datos de ejemplo solo si no hay marca seed_aplicado=1 (G4).
 */
public class LibreroApplication {

    /** Tablas permitidas para PRAGMA table_info (OPT5: lista blanca). */
    private static final Set<String> TABLAS_PERMITIDAS = Set.of("libros", "movimientos", "metadata");

    public static void main(String[] args) throws Exception {
        System.out.println("[LibroS] Iniciando la aplicación de inventario de libros...");

        Properties props = new Properties();
        try (InputStream in = LibreroApplication.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.out.println("[LibroS] Advertencia: no se pudo leer application.properties: " + e.getMessage());
        }
        int puerto = resolvePuerto(props);
        String url = props.getProperty("datasource.url", "jdbc:sqlite:base-de-datos/librero.db");
        int umbralPocoStock = Integer.parseInt(props.getProperty("librero.umbral.poco-stock", "5"));
        String token = System.getenv("LIBRERO_TOKEN");
        if (token != null && !token.isBlank()) {
            System.out.println("[LibroS] Token activado (G5): los endpoints /api/ exigen X-Librero-Token");
            System.out.println("[LibroS] Variable de entorno utilizada: LIBRERO_TOKEN");
        } else {
            System.out.println("[LibroS] Token desactivado: no se configuró LIBRERO_TOKEN.");
        }

        Connection conexion = DriverManager.getConnection(url);
        // G2: WAL + busy_timeout para acceso seguro desde múltiples hilos (HttpServer).
        try (Statement pragmaStmt = conexion.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode = WAL;");
            pragmaStmt.execute("PRAGMA busy_timeout = 5000;");
        }

        System.out.println("[LibroS] Base de datos (SQLite): " + url);

        // O3: activar PRAGMA foreign_keys antes del esquema.
        try (Statement st = conexion.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }

        // Aplica el esquema (DDL) y las migraciones (G6). R6: ruta resuelta sin depender del CWD.
        aplicarSql(conexion, leerSql("esquema.sql"));
        migrarColumnas(conexion);

        // G4: datos de ejemplo, solo si no está marcada la semilla seed_aplicado=1.
        if (!"1".equals(leerMetadata(conexion, "seed_aplicado"))) {
            aplicarSql(conexion, leerSql("datos-ejemplo.sql"));
            actualizarMetadata(conexion, "seed_aplicado", "1");
            System.out.println("[LibroS] Datos de ejemplo aplicados (libros de ejemplo)");
        } else {
            System.out.println("[LibroS] Datos de ejemplo ya aplicados previamente (seed_aplicado=1).");
        }

        // G2: los repositorios NO sincronizan por instancia. El único punto de serialización
        // es LibreroServicer (todos sus métodos públicos son synchronized sobre el mismo objeto),
        // que además delimita las transacciones sobre esta Connection.
        LibroRepositorio libros = new LibroRepositorio(conexion);
        MovimientoRepositorio movimientos = new MovimientoRepositorio(conexion);
        LibreroServicer servicio = new LibreroServicer(conexion, libros, movimientos, umbralPocoStock);

        // Servidor HTTP: puerto 8080, disponible en todas las interfaces de la red local.
        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        server.createContext("/", new LibreroControlador(servicio, puerto, token));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[LibroS] Servidor HTTP en http://localhost:" + puerto + " (esperando unos segundos...)");
        esperarPuertoListo(puerto, 15);

        // Abre el navegador y muestra la IP local para entrar desde otra computadora.
        abrirNavegador("http://localhost:" + puerto);
        String ip = LibreroControlador.ipLocal();
        System.out.println("[LibroS] Aplicación lista. Abre: http://localhost:" + puerto);
        System.out.println("[LibroS] Desde otra computadora en la misma red WiFi: http://" + ip + ":" + puerto);
        System.out.println("[LibroS] (Presiona Ctrl+C para salir; todos los datos están en " + url + ")");

        // Mantiene el servidor corriendo.
        CountDownLatch parada = new CountDownLatch(1);
        parada.await();
    }

    /* ------------------------------------------------------------------ */
    /* Resolución de puerto (O7).                                         */
    /* ------------------------------------------------------------------ */

    /** Prioriza System.getProperty("server.port"), luego LIBRERO_PORT, luego properties y 8080. */
    public static int resolvePuerto(Properties props) {
        String propiedad = System.getProperty("server.port");
        if (propiedad == null || propiedad.isBlank()) {
            propiedad = System.getenv("LIBRERO_PORT");
        }
        if (propiedad == null || propiedad.isBlank()) {
            propiedad = props.getProperty("server.port");
        }
        if (propiedad == null || propiedad.isBlank()) {
            return 8080;
        }
        try {
            int valor = Integer.parseInt(propiedad.trim());
            if (valor < 1 || valor > 65535) {
                throw new IllegalArgumentException("El puerto debe estar entre 1 y 65535: '" + propiedad.trim() + "'.");
            }
            return valor;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Puerto inválido '" + propiedad.trim() + "'. Use un número entero entre 1 y 65535 "
                            + "(ej.: LIBRERO_PORT=8081 o -Dserver.port=8081).", e);
        }
    }

    /**
     * R6: resuelve la carpeta base del proyecto sin depender del directorio de trabajo actual.
     * Prioriza -Dlibrero.base, luego el CWD si contiene base-de-datos/, y finalmente la
     * ubicación del jar/clases (target/classes o target/*.jar -> raíz del proyecto).
     */
    private static Path resolverBase() {
        String base = System.getProperty("librero.base");
        if (base != null && !base.isBlank()) {
            return Paths.get(base.trim());
        }
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("base-de-datos"))) {
            return cwd;
        }
        try {
            Path code = Paths.get(LibreroApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path candidato;
            if (Files.isDirectory(code)) {
                candidato = "classes".equals(code.getFileName().toString())
                        ? code.getParent().getParent() : code.getParent();
            } else {
                candidato = code.getParent();
            }
            if (candidato != null && Files.isDirectory(candidato.resolve("base-de-datos"))) {
                return candidato;
            }
            if (candidato != null && candidato.getParent() != null
                    && Files.isDirectory(candidato.getParent().resolve("base-de-datos"))) {
                return candidato.getParent();
            }
        } catch (Exception ignored) {
            // Se mantiene el directorio de trabajo como último recurso.
        }
        return cwd;
    }

    /** R6: lee un script SQL de base-de-datos/ usando la ruta base resuelta. */
    private static String leerSql(String nombre) throws IOException {
        return Files.readString(resolverBase().resolve("base-de-datos").resolve(nombre), StandardCharsets.UTF_8);
    }

    /* ------------------------------------------------------------------ */
    /* Aplicación de SQL: parser sencillo de scripts (O2).                 */
    /* ------------------------------------------------------------------ */

    public static void aplicarSql(Connection conexion, String sql) throws SQLException {
        try (Statement st = conexion.createStatement()) {
            for (String sentencia : dividirSql(sql)) {
                st.execute(sentencia);
            }
        }
    }

    /**
     * OPT5: separa sentencias respetando literales '...' (incluido el escape '' )
     * y eliminando comentarios -- sin usar regex sobre el texto ya dividido.
     */
    private static List<String> dividirSql(String sql) {
        List<String> sentencias = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean enLiteral = false;
        boolean enComentario = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (enComentario) {
                if (c == '\n') {
                    enComentario = false;
                    actual.append('\n');
                }
                continue;
            }
            if (enLiteral) {
                actual.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        actual.append('\'');
                        i++;
                    } else {
                        enLiteral = false;
                    }
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                enComentario = true;
                i++;
                continue;
            }
            if (c == '\'') {
                enLiteral = true;
                actual.append(c);
                continue;
            }
            if (c == ';') {
                agregarSiNoVacio(sentencias, actual);
                continue;
            }
            actual.append(c);
        }
        agregarSiNoVacio(sentencias, actual);
        return sentencias;
    }

    private static void agregarSiNoVacio(List<String> sentencias, StringBuilder actual) {
        String s = actual.toString().trim();
        if (!s.isEmpty()) {
            sentencias.add(s);
        }
        actual.setLength(0);
    }

    /* ------------------------------------------------------------------ */
    /* Migraciones (G6) y metadata (G4).                                   */
    /* ------------------------------------------------------------------ */

    public static void migrarColumnas(Connection conexion) throws SQLException {
        if (!tieneColumna(conexion, "libros", "activo")) {
            System.out.println("[LibroS] Migrando: añadiendo campo 'activo' a libros.");
            aplicarSql(conexion, "ALTER TABLE libros ADD COLUMN activo INTEGER NOT NULL DEFAULT 1");
        }
        if (!tieneColumna(conexion, "libros", "precio_venta")) {
            System.out.println("[LibroS] Migrando: añadiendo campo 'precio_venta' a libros.");
            aplicarSql(conexion, "ALTER TABLE libros ADD COLUMN precio_venta REAL");
        }
        if (!tieneColumna(conexion, "libros", "vendidos_acumulados")) {
            System.out.println("[LibroS] Migrando: añadiendo campo 'vendidos_acumulados' a libros.");
            aplicarSql(conexion, "ALTER TABLE libros ADD COLUMN vendidos_acumulados INTEGER NOT NULL DEFAULT 0");
        }
    }

    public static boolean tieneColumna(Connection conexion, String tabla, String columna) throws SQLException {
        if (!TABLAS_PERMITIDAS.contains(tabla)) {
            throw new IllegalArgumentException("Tabla no permitida en PRAGMA: " + tabla);
        }
        try (Statement st = conexion.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                if (columna.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String leerMetadata(Connection conexion, String clave) {
        try (PreparedStatement ps = conexion.prepareStatement(
                "SELECT value FROM metadata WHERE key = ? LIMIT 1")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            System.out.println("[LibroS] Advertencia: no se pudo leer metadata: " + e.getMessage());
        }
        return null;
    }

    public static void actualizarMetadata(Connection conexion, String clave, String valor) throws SQLException {
        try (PreparedStatement ps = conexion.prepareStatement(
                "INSERT OR REPLACE INTO metadata(key, value) VALUES (?, ?)")) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Utilidades.                                                        */
    /* ------------------------------------------------------------------ */

    private static void esperarPuertoListo(int puerto, int maxSegundos) {
        for (int i = 0; i < maxSegundos * 10; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", puerto), 200);
                return;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored2) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void abrirNavegador(String url) {
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(new URI(url));
            } else {
                System.out.println("[LibroS] Escritorio no detectado; abre manualmente: " + url);
            }
        } catch (Exception e) {
            System.out.println("[LibroS] No se pudo abrir el navegador: " + e.getMessage());
            System.out.println("[LibroS] Abre manualmente: " + url);
        }
    }
}
