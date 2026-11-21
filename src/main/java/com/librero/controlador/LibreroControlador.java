package com.librero.controlador;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.librero.servicio.ErrorInternoExcepcion;
import com.librero.servicio.LibreroServicer;
import com.librero.servicio.ServicioExcepcion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controlador: recibe las peticiones HTTP y las dirige al servicio (LibreroServicer...).
 *
 * Endpoints:
 * - GET / → UI (index.html)
 * - GET /estilos.css, /app.js, /index.html → archivos estáticos
 * - GET /api/info → {ip, port} para la IP local del servidor
 * - GET /api/dashboard → totales reales del panel principal
 * - POST /api/llegada → registra llegada de libros
 * - POST /api/salida → registra salida / venta
 * - GET /api/inventario → lista libros (filtros q_isbn, q_titulo, q_editorial, orden)
 * - GET /api/inventario/{isbn} → busca un libro por ISBN
 * - PUT /api/inventario/{isbn} → edita título, editorial y/o autor
 * - DELETE /api/inventario/{isbn} → elimina el libro (borrado lógico, G6)
 * - GET /api/movimientos → lista movimientos (filtros tipo, isbn, desde, hasta)
 * - GET /api/libros/sugerir → sugiere ISBNs y títulos para el buscador de salida
 * - GET /api/exportar/inventario.csv, /api/exportar/movimientos.csv → CSV para descargar
 *
 * G5: todos los endpoints /api/ exigen el token X-Librero-Token si se configuró
 * token desde la variable de entorno LIBRERO_TOKEN (ver LibreroApplication).
 * M8: errores de base de datos devuelven HTTP 500 con mensaje genérico.
 */
public class LibreroControlador implements HttpHandler {

    /** Token configurado (G5); null = sin token, sin autenticación. */
    private final String token;
    private final LibreroServicer servicio;
    private final int puerto;
    private final Gson gson = new Gson();

    public LibreroControlador(LibreroServicer servicio, int puerto, String token) {
        this.servicio = servicio;
        this.puerto = puerto;
        this.token = token;
    }

    /** G5: true si el token entrante es válido (o si no hay token configurado). */
    public boolean esTokenValido(String headerToken) {
        if (token == null || token.isBlank()) {
            return true;
        }
        return headerToken != null && headerToken.equals(token);
    }

    /** G5: true si la petición que se está atendiendo es válida para la API. */
    private boolean esTokenValido(HttpExchange exchange) {
        return esTokenValido(exchange.getRequestHeaders().getFirst("X-Librero-Token"));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String metodo = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());

            // G5: proteger la API exigiendo el token (si se configuró).
            if (path.startsWith("/api/") && !esTokenValido(exchange)) {
                responderJson(exchange, 401, error("Token de Librero inválido o ausente."));
                return;
            }

            switch (path) {
                case "/" -> servirEstático(exchange, "static/index.html", "text/html; charset=UTF-8");
                case "/index.html" -> servirEstático(exchange, "static/index.html", "text/html; charset=UTF-8");
                case "/estilos.css" -> servirEstático(exchange, "static/estilos.css", "text/css; charset=UTF-8");
                case "/app.js" -> servirEstático(exchange, "static/app.js", "text/javascript; charset=UTF-8");
                case "/api/info" -> responderJson(exchange, 200, info());
                case "/api/dashboard" -> responderJson(exchange, 200, servicio.dashboard());
                case "/api/llegada" -> handleLlegada(exchange);
                case "/api/salida" -> handleSalida(exchange);
                case "/api/inventario" -> handleInventario(exchange, query);
                case "/api/movimientos" -> responderJson(exchange, 200, servicio.historial(
                        query.get("tipo"), query.get("isbn"), query.get("desde"), query.get("hasta")));
                case "/api/exportar/inventario.csv" -> handleExportarCsv(exchange, servicio.inventarioCSV(), "inventario.csv");
                case "/api/exportar/movimientos.csv" -> handleExportarCsv(exchange, servicio.movimientosCSV(), "movimientos.csv");
                default -> {
                    if (path.startsWith("/api/inventario/")) {
                        handleInventarioRuta(exchange, path.substring("/api/inventario/".length()), metodo);
                    } else if (path.startsWith("/api/libros/sugerir")) {
                        handleSugerir(exchange, query.get("q"));
    
                } else {
                        responderJson(exchange, 404, error("No encontrado."));
                    }
                }
            }
        } catch (ServicioExcepcion e) {
            // M8: validaciones de negocio -> 400 con mensaje claro.
            responderJson(exchange, 400, error(e.getMessage()));
        } catch (ErrorInternoExcepcion e) {
            // M8: fallos de base de datos -> 500 con mensaje genérico (sin detalles internos).
            responderJson(exchange, 500, error("Error interno. Consulte al administrador."));
        } catch (Exception e) {
            // M8: cualquier otro fallo -> 500 genérico.
            System.err.println("[LibroS] Error no esperado: " + e.getClass().getName() + ": " + e.getMessage());
            responderJson(exchange, 500, error("Error interno. Consulte al administrador."));
        } finally {
            exchange.close();
        }
    }

    /* ------------------------------ POST /api/llegada ------------------------------ */

    private void handleLlegada(HttpExchange exchange) throws IOException {
        JsonObject j = leerJson(exchange);
        Map<String, Object> resultado = servicio.registrarLlegada(
                LibreroServicer.textoDe(j, "isbn"),
                LibreroServicer.textoDe(j, "titulo"),
                LibreroServicer.textoDe(j, "editorial"),
                LibreroServicer.textoDe(j, "autor"),
                LibreroServicer.enteroDe(j, "cantidad", -1),
                LibreroServicer.enteroDe(j, "cajas", -1),
                LibreroServicer.textoDe(j, "fecha"),
                LibreroServicer.textoDe(j, "hora"),
                LibreroServicer.textoDe(j, "nota"));
        responderJson(exchange, 200, resultado);
    }

    /* ------------------------------ POST /api/salida ------------------------------ */

    private void handleSalida(HttpExchange exchange) throws IOException {
        JsonObject j = leerJson(exchange);
        Map<String, Object> resultado = servicio.registrarSalida(
                LibreroServicer.textoDe(j, "isbn"),
                LibreroServicer.enteroDe(j, "cantidad", -1),
                LibreroServicer.textoDe(j, "fecha"),
                LibreroServicer.textoDe(j, "hora"),
                LibreroServicer.textoDe(j, "nota"));
        responderJson(exchange, 200, resultado);
    }

    /* ------------------------------ GET /api/inventario ------------------------------ */

    private void handleInventario(HttpExchange exchange, Map<String, String> query) throws IOException {
        responderJson(exchange, 200, servicio.inventario(
                query.get("q_isbn"), query.get("q_titulo"), query.get("q_editorial"), query.get("orden")));
    }

    /* ------------------------------ GET/PUT/DELETE /api/inventario/{isbn} ------------------------------ */

    private void handleInventarioRuta(HttpExchange exchange, String isbn, String metodo) throws IOException {
        switch (metodo) {
            case "GET" -> responderJson(exchange, 200, servicio.buscarLibro(isbn));
            case "PUT" -> {
                JsonObject j = leerJson(exchange);
                responderJson(exchange, 200, servicio.actualizarLibro(
                        isbn,
                        LibreroServicer.textoDe(j, "titulo"),
                        LibreroServicer.textoDe(j, "editorial"),
                        LibreroServicer.textoDeOpcional(j, "autor")));
            }
            case "DELETE" -> {
                responderJson(exchange, 200, servicio.eliminarLibro(isbn));
            }
            default -> responderJson(exchange, 405, error("Método no permitido."));
        }
    }

    /* ------------------------------ GET /api/libros/sugerir ------------------------------ */

    private void handleSugerir(HttpExchange exchange, String busqueda) throws IOException {
        // OPT1: la búsqueda y el límite se hacen en SQL; no se carga el inventario completo.
        responderJson(exchange, 200, servicio.sugerirLibros(busqueda));
    }

    /* ------------------------------ Exportación CSV ------------------------------ */

    private void handleExportarCsv(HttpExchange exchange, String contenidoCsv, String nombreArchivo) throws IOException {
        byte[] bytes = contenidoCsv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + nombreArchivo + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /* ------------------------------ Helpers ------------------------------ */

    private Map<String, Object> error(String mensaje) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ok", false);
        e.put("error", mensaje);
        return e;
    }

    private Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ip", ipLocal());
        info.put("port", this.puerto);
        info.put("app", "Librero");
        return info;
    }

    /** Devuelve la IP local (primera IPv4 no loopback), o 127.0.0.1 si no hay. */
    public static String ipLocal() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addrs = interfaces.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // sin interfases: devolvemos loopback
        }
        return "127.0.0.1";
    }

    private void responderJson(HttpExchange exchange, int codigo, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(codigo, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void servirEstático(HttpExchange exchange, String recurso, String contentType) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(recurso)) {
            if (is == null) {
                responderJson(exchange, 404, error("Recurso no encontrado: " + recurso));
                return;
            }
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private String leerCuerpo(HttpExchange exchange) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining(""));
        }
    }

    private JsonObject leerJson(HttpExchange exchange) throws IOException {
        return LibreroServicer.parsearJson(leerCuerpo(exchange));
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return mapa;
        }
        for (String parte : query.split("&")) {
            if (parte.isBlank()) {
                continue;
            }
            int i = parte.indexOf('=');
            String clave = i >= 0 ? parte.substring(0, i) : parte;
            String valor = i >= 0 ? parte.substring(i + 1) : "";
            mapa.put(URLDecoder.decode(clave, StandardCharsets.UTF_8), URLDecoder.decode(valor, StandardCharsets.UTF_8));
        }
        return mapa;
    }
}

    /* ------------------------------ GET /api/libros/{isbn}/ficha ------------------------------ */
    private Map<String, Object> fichaLibro(String isbn) throws Exception {
        Libro libro = libros.buscarPorIden(isbn);
        if (libro == null || !libro.isActivo()) {
            throw new ServicioExcepcion("Libro no encontrado");
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("libro", libroAJson(libro));
        Map<String, Object> ultEnt = ultimaMov("ENTRADA", isbn);
        Map<String, Object> ultSal = ultimaMov("SALIDA", isbn);
        f.put("ultima_entrada", ultEnt);
        f.put("ultima_salida", ultSal);
        return f;
    }

    private Map<String, Object> ultimaMov(String tipo, String isbn) throws Exception {
        List<Map<String, Object>> l = movimientos.listarConTitulo(tipo, isbn, null, null);
        if (l.isEmpty()) return null;
        Map<String, Object> e = l.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fecha", e.get("fecha"));
        out.put("cantidad", e.get("cantidad"));
        out.put("cajas", e.get("cajas"));
        return out;
    }

    private Map<String, Object> libroAJson(Libro libro) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isbn", libro.getIsbn()); m.put("titulo", libro.getTitulo());
        m.put("editorial", libro.getEditorial()); m.put("autor", libro.getAutor());
        m.put("stock_actual", libro.getStockActual()); m.put("total_cajas", libro.getTotalCajas());
        m.put("precio_venta", libro.getPrecioVenta());
        return m;
    }
