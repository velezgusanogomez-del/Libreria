package com.librero.servicio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.librero.modelo.Libro;
import com.librero.modelo.Movimiento;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lógica de negocio del sistema de inventario de libros (Librero).
 * Todos los métodos públicos son synchronized (G2): un único objeto sincronizado envuelve
 * toda la operación de negocio, asegurando que una operación de stock o la transacción
 * completa ocurran de forma serializada.
 *
 * - registrarLlegada: alta atómica de llegada (G1). Crea el libro nuevo o suma stock/cajas
 *   a uno existente. Guarda un movimiento de tipo ENTRADA dentro de una transacción.
 * - registrarSalida: resta stock de forma atómica (G1). No permite que quede en negativo.
 * - eliminarLibro: borrado lógico: marca activo=0 (G6), sin borrar movimientos.
 * - inventario / buscarLibro: datos reales de la base de datos, excluyen inactivos (G6).
 * - registrarLlegada / registrarSalida: rechazan libros inactivos (R1) para que el stock
 *   y las ventas nunca cambien en elementos que no se ven en el inventario.
 * - sugerirLibros: autocompletado acotado en SQL (OPT1).
 * - historial: movimientos con filtros (tipo, ISBN, fecha desde/hasta), validando fechas (M6).
 * - dashboard: totales reales reutilizando un único conjunto de datos (OPT2).
 * - inventarioCSV / movimientosCSV: generan archivos CSV para exportar (M3).
 */
public class LibreroServicer {

    private static final Pattern PATRON_HORA = Pattern.compile("^\\d{2}:\\d{2}$");

    private final LibroRepositorio libros;
    private final MovimientoRepositorio movimientos;
    private final int umbralPocoStock;
    private final Connection conexion;
    private boolean transaccionActiva = false;

    public LibreroServicer(Connection conexion, LibroRepositorio libros,
                           MovimientoRepositorio movimientos, int umbralPocoStock) {
        this.conexion = conexion;
        this.libros = libros;
        this.movimientos = movimientos;
        this.umbralPocoStock = umbralPocoStock;
    }

    /* ------------------------------------------------------------------ */
    /* 1. Registrar llegada de libros (G1).                               */
    /* ------------------------------------------------------------------ */

    /**
     * Registra una llegada (G1): crea el libro si su ISBN no existe o suma stock/cajas si
     * ya existe, usando SQL atómico (INSERT ... ON CONFLICT). Guarda un movimiento de tipo
     * ENTRADA dentro de una transacción por operación.
     */
    public synchronized Map<String, Object> registrarLlegada(String isbn, String titulo, String editorial,
                                                              String autor, int cantidad, int cajas,
                                                              String fecha, String hora, String nota) {
        // Validaciones de entrada.
        validarTextoObligatorio(isbn, "El ISBN es obligatorio.");
        validarFechaYHora(fecha, hora);
        if (cantidad < 1) {
            throw new ServicioExcepcion("La cantidad de libros que llegaron debe ser mayor que 0.");
        }
        if (cajas < 1) {
            throw new ServicioExcepcion("La cantidad de cajas que llegaron debe ser mayor que 0.");
        }

        iniciarTransaccion();
        try {
            Libro libro = libros.buscarPorIden(isbn);
            if (libro == null) {
                // Libro nuevo: obligatorio título y editorial.
                validarTextoObligatorio(titulo, "El título del libro es obligatorio al registrar la llegada.");
                validarTextoObligatorio(editorial, "La editorial del libro es obligatoria al registrar la llegada.");
            }
            // Altas atómicas (G1): INSERT ... ON CONFLICT suma stock_actual y total_cajas.
            libros.upsertLlegada(isbn.trim(),
                    isBlank(titulo) ? null : titulo.trim(),
                    isBlank(editorial) ? null : editorial.trim(),
                    isBlank(autor) ? null : autor.trim(),
                    cantidad, cajas, LocalDate.now().toString());
            // Movimiento de tipo ENTRADA (se inserta antes del commit).
            long id = movimientos.insertar(new Movimiento(isbn.trim(), "ENTRADA", cantidad, cajas,
                    fecha, hora, isBlank(nota) ? null : nota.trim()));
            conexion.commit();

            // Releer después del commit para responder con datos reales de la base de datos.
            libro = libros.buscarPorIden(isbn.trim());
            Map<String, Object> resultado = libroAJson(libro);
            resultado.put("ok", true);
            resultado.put("mensaje", "Llegada registrada: " + libro.getTitulo() + " - stock actual: "
                    + libro.getStockActual() + " libros, cajas totales: " + libro.getTotalCajas() + ".");
            resultado.put("id_movimiento", id);
            return resultado;
        } catch (Exception e) {
            rollbackTransaccion();
            if (e instanceof ServicioExcepcion) {
                throw (ServicioExcepcion) e;
            }
            throw new ErrorInternoExcepcion("Error al guardar la llegada: " + e.getMessage());
        } finally {
            finTransaccion();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2. Registrar salida / venta de libros (G1).                         */
    /* ------------------------------------------------------------------ */

    /**
     * Registra una salida (G1): resta el stock de forma atómica (UPDATE condicional
     * WHERE stock_actual >= ?) y guarda un movimiento de tipo SALIDA.
     * No permite que el stock quede negativo.
     */
    public synchronized Map<String, Object> registrarSalida(String isbn, int cantidad,
                                                              String fecha, String hora, String nota) {
        validarTextoObligatorio(isbn, "El ISBN es obligatorio.");
        validarFechaYHora(fecha, hora);
        if (cantidad < 1) {
            throw new ServicioExcepcion("La cantidad de libros que salieron debe ser mayor que 0.");
        }

        iniciarTransaccion();
        try {
            Libro libro = libros.buscarPorIden(isbn);
            if (libro == null || !libro.isActivo()) {
                // R1: los libros inactivos no pueden generar ventas.
                throw new ServicioExcepcion("El libro con ISBN '" + isbn + "' no está registrado. " +
                        "Registre primero la llegada del libro en \"Registrar llegada\".");
            }
            // Resta atómica (G1): UPDATE ... WHERE isbn = ? AND activo = 1 AND stock_actual >= ?
            boolean ok = libros.decrementarStockYRegistrarVenta(isbn, cantidad);
            if (!ok) {
                throw new ServicioExcepcion("No hay suficientes libros en stock: hay " + libro.getStockActual() +
                        " y se intentan sacar " + cantidad + ". La venta no fue registrada.");
            }
            // Guarda el movimiento de tipo SALIDA (cajas: null, sólo las entradas tienen cajas).
            long id = movimientos.insertar(new Movimiento(isbn, "SALIDA", cantidad, null,
                    fecha, hora, isBlank(nota) ? null : nota.trim()));
            conexion.commit();

            libro = libros.buscarPorIden(isbn);
            Map<String, Object> resultado = libroAJson(libro);
            resultado.put("ok", true);
            resultado.put("mensaje", "Venta registrada: " + cantidad + " libros de '" + libro.getTitulo() +
                    "'. Stock restante: " + libro.getStockActual() + ".");
            resultado.put("id_movimiento", id);
            return resultado;
        } catch (Exception e) {
            rollbackTransaccion();
            if (e instanceof ServicioExcepcion) {
                throw (ServicioExcepcion) e;
            }
            throw new ErrorInternoExcepcion("Error al guardar la salida: " + e.getMessage());
        } finally {
            finTransaccion();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3. Ver inventario.                                                 */
    /* ------------------------------------------------------------------ */

    /** Lista todos los libros activos con datos reales de la base de datos, ordenados por stock (asc). */
    public synchronized List<Map<String, Object>> inventario() throws ServicioExcepcion {
        return inventario(null, null, null, "asc");
    }

    /**
     * Lista libros activos con filtros (ISBN, título, editorial) y orden ("desc" = primero
     * los que tienen más stock; cualquier otro = primero los que tienen menos stock).
     * Cada ítem contiene: isbn, titulo, editorial, autor, stock_actual, total_cajas,
     * fecha_creacion, ultima_fecha_movimiento y poco_stock.
     */
    public synchronized List<Map<String, Object>> inventario(String filtroIsbn, String filtroTitulo,
                                                               String filtroEditorial, String orden) {
        try {
            List<Libro> librosEnOrden = libros.listarFiltrado(filtroIsbn, filtroTitulo, filtroEditorial, orden);
            // M2: una sola consulta GROUP BY para todas las últimas fechas de movimiento.
            return construirInventario(librosEnOrden, movimientos.ultimasFechasDeMovimientos());
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al listar el inventario: " + e.getMessage());
        }
    }

    /** OPT2: construye el inventario a partir de un conjunto de datos ya obtenido. */
    private List<Map<String, Object>> construirInventario(List<Libro> librosEnOrden,
                                                           Map<String, String> ultimas) {
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (Libro libro : librosEnOrden) {
            Map<String, Object> mapa = libroAJson(libro);
            String ultima = ultimas.get(libro.getIsbn());
            mapa.put("ultima_fecha_movimiento", ultima == null ? "" : ultima);
            mapa.put("poco_stock", libro.getStockActual() < umbralPocoStock);
            resultado.add(mapa);
        }
        return resultado;
    }

    /* ------------------------------------------------------------------ */
    /* 4. Buscar libro individual (para el formulario de salida).         */
    /* ------------------------------------------------------------------ */

    /** Busca un libro activo por ISBN; error claro si no está registrado o está eliminado. */
    public synchronized Map<String, Object> buscarLibro(String isbn) {
        validarTextoObligatorio(isbn, "El ISBN es obligatorio.");
        try {
            Libro libro = libros.buscarPorIden(isbn.trim());
            if (libro == null || !libro.isActivo()) {
                throw new ServicioExcepcion("El libro con ISBN '" + isbn + "' no está registrado. " +
                        "Registre primero la llegada del libro.");
            }
            Map<String, String> ultimas = movimientos.ultimasFechasDeMovimientos();
            Map<String, Object> mapa = libroAJson(libro);
            String ultima = ultimas.get(libro.getIsbn());
            mapa.put("ultima_fecha_movimiento", ultima == null ? "" : ultima);
            mapa.put("poco_stock", libro.getStockActual() < umbralPocoStock);
            return mapa;
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al buscar el libro: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 5. Editar libro (título, editorial, autor).                        */
    /* ------------------------------------------------------------------ */

    /**
     * Actualiza título, editorial y/o autor de un libro activo.
     * M4: distingue "no enviado" (null, se mantiene) de "enviado vacío" (se borra el autor).
     */
    public synchronized Map<String, Object> actualizarLibro(String isbn, String titulo, String editorial, String autor) {
        validarTextoObligatorio(isbn, "El ISBN es obligatorio.");
        try {
            Libro libro = libros.buscarPorIden(isbn.trim());
            if (libro == null || !libro.isActivo()) {
                throw new ServicioExcepcion("El libro con ISBN '" + isbn + "' no está registrado.");
            }
            if (!esVacio(titulo)) {
                libro.setTitulo(titulo.trim());
            }
            if (!esVacio(editorial)) {
                libro.setEditorial(editorial.trim());
            }
            if (autor != null) {
                libro.setAutor(autor.isEmpty() ? null : autor.trim());
            }
            libros.actualizar(libro);
            return libroAJson(libro);
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al editar el libro: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 6. Eliminar libro (G6: borrado lógico).                            */
    /* ------------------------------------------------------------------ */

    /**
     * G6: marca el libro como inactivo (activo=0). Los movimientos se conservan en la tabla
     * histórica (no se borran físicamente), así el total histórico de ventas no cambia.
     */
    public synchronized Map<String, Object> eliminarLibro(String isbn) {
        validarTextoObligatorio(isbn, "El ISBN es obligatorio.");
        try {
            Libro libro = libros.buscarPorIden(isbn.trim());
            if (libro == null) {
                throw new ServicioExcepcion("El libro con ISBN '" + isbn + "' no está registrado.");
            }
            if (!libro.isActivo()) {
                throw new ServicioExcepcion("El libro con ISBN '" + isbn + "' ya está eliminado.");
            }
            libros.marcarInactivo(libro.getIsbn());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("mensaje", "Libro eliminado: " + libro.getTitulo() + " (ISBN " + libro.getIsbn() +
                    "). El histórico de movimientos se conserva.");
            return r;
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al eliminar el libro: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 7. Historial de movimientos.                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Lista los movimientos con filtros (tipo, ISBN, rango de fechas).
     * M6: valida fechas del historial y rechaza desde > hasta.
     */
    public synchronized List<Map<String, Object>> historial(String tipo, String isbn, String desde, String hasta) {
        try {
            if (!esVacio(desde)) {
                validarFecha(desde);
            }
            if (!esVacio(hasta)) {
                validarFecha(hasta);
            }
            if (!esVacio(desde) && !esVacio(hasta) && desde.compareTo(hasta) > 0) {
                throw new ServicioExcepcion("'desde' no puede ser mayor que 'hasta'.");
            }
            return movimientos.listarConTitulo(tipo, isbn, desde, hasta);
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al listar el historial: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 8. Dashboard / panel principal (totales reales de la BD).           */
    /* ------------------------------------------------------------------ */

    public synchronized Map<String, Object> dashboard() {
        try {
            // OPT2: una sola lectura de activos y un solo mapa de últimas fechas para
            // calcular los totales y la lista de poco stock (antes se hacía dos veces).
            List<Libro> activos = libros.listarFiltrado(null, null, null, "asc");
            List<Map<String, Object>> inventario = construirInventario(
                    activos, movimientos.ultimasFechasDeMovimientos());

            int totalLibros = activos.size();
            int totalStock = 0;
            int totalCajas = 0;
            for (Libro libro : activos) {
                totalStock += libro.getStockActual();
                totalCajas += libro.getTotalCajas();
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("total_libros_diferentes", totalLibros);
            d.put("total_stock", totalStock);
            d.put("total_cajas_arribados", totalCajas);
            // G6: vendidos acumulados (sobreviven al borrado del libro).
            d.put("total_libros_vendidos", libros.sumarVendidosAcumulados());
            d.put("libros_con_poco_stock", inventario.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("poco_stock")))
                    .map(LibreroServicer::libroMini)
                    .collect(Collectors.toList()));
            d.put("umbral_poco_stock", umbralPocoStock);
            return d;
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al cargar el panel: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 9. Exportación a CSV.                                               */
    /* ------------------------------------------------------------------ */

    /** Genera CSV del inventario (ISBN, título, editorial, autor, stock, cajas, última movimiento). */
    public String inventarioCSV() {
        List<Map<String, Object>> lista = inventario();
        StringBuilder sb = new StringBuilder();
        sb.append("isbn,titulo,editorial,autor,stock_actual,total_cajas,ultima_fecha_movimiento\n");
        for (Map<String, Object> m : lista) {
            sb.append(csv(m.get("isbn"))).append(',');
            sb.append(csv(m.get("titulo"))).append(',');
            sb.append(csv(m.get("editorial"))).append(',');
            sb.append(csv(m.get("autor"))).append(',');
            sb.append(csv(m.get("stock_actual"))).append(',');
            sb.append(csv(m.get("total_cajas"))).append(',');
            sb.append(csv(m.get("ultima_fecha_movimiento"))).append('\n');
        }
        return sb.toString();
    }

    /** Genera CSV del historial de movimientos (ISBN, título, tipo, cantidad, cajas, fecha, hora, nota). */
    public String movimientosCSV() {
        List<Map<String, Object>> lista = historial(null, null, null, null);
        StringBuilder sb = new StringBuilder();
        sb.append("isbn,titulo,tipo,cantidad,cajas,fecha,hora,nota\n");
        for (Map<String, Object> m : lista) {
            sb.append(csv(m.get("isbn"))).append(',');
            sb.append(csv(m.get("titulo"))).append(',');
            sb.append(csv(m.get("tipo"))).append(',');
            sb.append(csv(m.get("cantidad"))).append(',');
            sb.append(csv(m.get("cajas"))).append(',');
            sb.append(csv(m.get("fecha"))).append(',');
            sb.append(csv(m.get("hora"))).append(',');
            sb.append(csv(m.get("nota"))).append('\n');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Transacciones (G2).                                                 */
    /* ------------------------------------------------------------------ */

    private void iniciarTransaccion() {
        try {
            conexion.setAutoCommit(false);
            transaccionActiva = true;
        } catch (SQLException e) {
            transaccionActiva = false;
            throw new ErrorInternoExcepcion("Error al iniciar la transaccion: " + e.getMessage(), e);
        }
    }

    private void rollbackTransaccion() {
        try {
            if (transaccionActiva) {
                conexion.rollback();
            }
        } catch (SQLException e) {
            System.err.println("[Librero] Advertencia al hacer rollback: " + e.getMessage());
        }
    }

    private void finTransaccion() {
        transaccionActiva = false;
        try {
            conexion.setAutoCommit(true);
        } catch (SQLException e) {
            System.err.println("[Librero] Advertencia al finalizar la transacción: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers internos.                                                  */
    /* ------------------------------------------------------------------ */

    /** Convierte un Libro en un mapa con las claves JSON que usa la API y la UI. */
    private Map<String, Object> libroAJson(Libro libro) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isbn", libro.getIsbn());
        m.put("titulo", libro.getTitulo());
        m.put("editorial", libro.getEditorial());
        m.put("autor", libro.getAutor());
        m.put("stock_actual", libro.getStockActual());
        m.put("total_cajas", libro.getTotalCajas());
        m.put("fecha_creacion", libro.getFechaCreacion());
        m.put("activo", libro.getActivo());
        m.put("vendidos_acumulados", libro.getVendidosAcumulados());
        return m;
    }

    /** OPT1: sugerencias con consulta SQL acotada, sin cargar el inventario completo. */
    public synchronized List<Map<String, Object>> sugerirLibros(String consulta) {
        try {
            return libros.sugerirLibros(consulta == null ? "" : consulta.trim(), 12);
        } catch (SQLException e) {
            throw new ErrorInternoExcepcion("Error al sugerir libros: " + e.getMessage());
        }
    }

    /** Mini-mapa para la tarjeta "poco stock" del panel (mantiene las columnas de la UI). */
    private static Map<String, Object> libroMini(Map<String, Object> libro) {
        Map<String, Object> mini = new LinkedHashMap<>();
        mini.put("isbn", libro.get("isbn"));
        mini.put("titulo", libro.get("titulo"));
        mini.put("editorial", libro.get("editorial"));
        mini.put("stock_actual", libro.get("stock_actual"));
        mini.put("poco_stock", true);
        return mini;
    }

    private void validarTextoObligatorio(String valor, String mensaje) {
        if (isBlank(valor)) {
            throw new ServicioExcepcion(mensaje);
        }
    }

    private void validarFecha(String fecha) {
        if (esVacio(fecha)) {
            return;
        }
        // LocalDate.parse valida el formato YYYY-MM-DD y que el día/mes realmente existe.
        try {
            LocalDate.parse(fecha.trim());
        } catch (DateTimeParseException e) {
            throw new ServicioExcepcion("Formato de fecha inválido; use YYYY-MM-DD.");
        }
    }

    private void validarHora(String hora) {
        if (esVacio(hora)) {
            return;
        }
        // O4: exigir estrictamente el formato HH:MM además del parseo del tiempo.
        String h = hora.trim();
        if (!PATRON_HORA.matcher(h).matches()) {
            throw new ServicioExcepcion("Formato de hora inválido; use HH:MM.");
        }
        try {
            LocalTime.parse(h);
        } catch (DateTimeParseException e) {
            throw new ServicioExcepcion("Formato de hora inválido; use HH:MM.");
        }
    }

    private void validarFechaYHora(String fecha, String hora) {
        if (esVacio(fecha)) {
            throw new ServicioExcepcion("La fecha es obligatoria; la hora es opcional.");
        }
        validarFecha(fecha);
        validarHora(hora);
    }

    private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private boolean isBlank(String valor) {
        return esVacio(valor);
    }

    /** Formatea un valor para CSV: entre comillas si tiene comas o comillas; M3: prefija fórmulas. */
    private String csv(Object valor) {
        if (valor == null) {
            return "";
        }
        String s = String.valueOf(valor).replace("\"", "\"\"");
        // M3: neutralizar fórmulas de Excel/LibreOffice (valores que empiezan con =, +, - o @).
        if (s.length() > 0 && "+-=@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        boolean necesitaComillas = s.contains(",") || s.contains("\"") || s.contains("\n");
        return necesitaComillas ? "\"" + s + "\"" : s;
    }

    /**
     * M1: rechaza decimales en un campo entero: 1.5 y 2.9 dan error en lugar de
     * truncarse a 1 y 2. Devuelve el valor por defecto si el campo falta o es null.
     */
    public static int enteroDe(JsonObject objeto, String clave, int porDefecto) {
        JsonElement elemento = objeto.get(clave);
        if (elemento == null || elemento.isJsonNull()) {
            return porDefecto;
        }
        if (!elemento.isJsonPrimitive()) {
            throw new ServicioExcepcion("El campo '" + clave + "' debe ser un número entero.");
        }
        try {
            BigDecimal bd = new BigDecimal(elemento.getAsString().trim());
            if (bd.stripTrailingZeros().scale() > 0) {
                throw new ServicioExcepcion("El campo '" + clave + "' debe ser un número entero.");
            }
            return bd.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ServicioExcepcion("El campo '" + clave + "' debe ser un número entero.");
        }
    }

    /** Devuelve texto de un campo de texto: null si no enviado, cadena (vacía si enviada vacía). */
    public static String textoDe(JsonObject objeto, String clave) {
        JsonElement elemento = objeto.get(clave);
        if (elemento != null && !elemento.isJsonNull()) {
            return elemento.getAsString().trim();
        }
        return null;
    }

    /**
     * M4: similar a textoDe pero distinguiendo "no enviado" de "enviado vacío":
     * devuelve null si no enviado, cadena vacía si enviado vacío, texto si enviado.
     */
    public static String textoDeOpcional(JsonObject objeto, String clave) {
        JsonElement elemento = objeto.get(clave);
        if (elemento == null || elemento.isJsonNull()) {
            return null; // no enviado
        }
        return elemento.getAsString().trim(); // "" si enviado vacío
    }

    /** Expone el parser de JSON para el controlador. */
    public static JsonObject parsearJson(String cuerpo) {
        try {
            return JsonParser.parseString(cuerpo).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new ServicioExcepcion("Cuerpo de la petición inválido; envíe JSON con los campos necesarios.");
        }
    }
}

    /** Ficha de consulta: libro + ultima ENTRADA + ultima SALIDA (fecha, cant, cajas). */
    public synchronized java.util.Map<String, Object> fichaLibro(String isbn) throws Exception {
        com.librero.modelo.Lib SHARES libro = libros.buscarPorIden(isbn);
        if (libro == null || !libro.isActivo()) throw new ServicioExcepcion("Libro no encontrado");
        java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("libro", libroAJson(libro));
        java.util.Map<String, Object> ue = ultimaEntrada(isbn);
        java.util.Map<String, Object> us = ultimaSalida(isbn);
        f.put("ultima_entrada", ue); f.put("ultima_salida", us);
        return f;
    }
    private java.util.Map<String, Object> ultimaEntrada(String isbn) throws Exception {
        java.util.List<java.util.Map<String, Object>> l = movimientos.listarConTitulo("ENTRADA", isbn, null, null);
        return l.isEmpty() ? null : brief(l.get(0));
    }
    private java.util.Map<String, Object> ultimaSalida(String isbn) throws Exception {
        java.util.List<java.util.Map<String, Object>> l = movimientos.listarConTitulo("SALIDA", isbn, null, null);
        return l.isEmpty() ? null : brief(l.get(0));
    }
    private java.util.Map<String, Object> brief(java.util.Map<String, Object> m) {
        java.util.Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("fecha", m.get("fecha")); o.put("cantidad", m.get("cantidad")); o.put("cajas", m.get("cajas"));
        return o;
    }

    public synchronized void actualizarPrecioVenta(String isbn, Double precioVenta) throws Exception {
        libros.actualizarPrecioVenta(isbn, precioVenta);
    }
