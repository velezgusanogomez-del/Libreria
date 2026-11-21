package com.librero.repositorio;

import com.librero.modelo.Libro;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de acceso a la tabla libros (SQLite) usando JDBC.
 *
 * - buscarPorIden: busca un libro por su ISBN.
 * - upsertLlegada: alta atómica de libro nuevo (INSERT) o suma de stock/cajas si el
 *   libro ya existe (UPDATE atómico), G1.
 * - decrementarStockYRegistrarVenta: resta stock atómicamente (sale), con UPDATE condicional
 *   y verifica executeUpdate() == 1 (G1).
 * - marcarInactivo: marca el libro como borrado lógico activo=0 (G6).
 * - sumarVendidosAcumulados: suma de ventas acumuladas que sobreviven al borrado (G6).
 * - sugerirLibros: consulta acotada (LIKE + LIMIT) para el autocompletado (OPT1).
 * - actualizar: actualiza textos de un libro (edición).
 * - listarFiltrado: excluye libros inactivos (G6); el panel reutiliza ese resultado (OPT2).
 *
 * G2: estos repositorios NO se sincronizan por instancia; el único punto de serialización
 * es LibreroServicer, que envuelve cada operación de negocio en su propio monitor.
 */
public class LibroRepositorio {

    private final Connection conexion;

    public LibroRepositorio(Connection conexion) {
        this.conexion = conexion;
    }

    /** Busca un libro por su ISBN; devuelve null si no existe. */
    public Libro buscarPorIden(String isbn) throws SQLException {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = conexion.prepareStatement(
                "SELECT isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion, activo, vendidos_acumulados, precio_venta " +
                        "FROM libros WHERE isbn = ?")) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapear(rs);
                }
            }
        }
        return null;
    }

        /**
     * Altas de llegada atómicas (G1):
     * - Si el libro ya existe: suma stock_actual y total_cajas de forma atómica:
     *   UPDATE libros SET stock_actual = stock_actual + ?, total_cajas = total_cajas + ? WHERE isbn = ?.
     * - Si no existe: inserta el libro nuevo (INSERT con los datos de la llegada).
     */
    public void upsertLlegada(String isbn, String titulo, String editorial, String autor,
                                            int stockEntrante, int cajasEntrantes, String fechaCreacion) throws SQLException {
        // R1: llegada a un libro inactivo reactiva (activo=1) y actualiza campos solo
        // si se enviaron (COALESCE mantiene el valor anterior si no se envió); suma stock/cajas.
        String upd = "UPDATE libros SET activo = 1, stock_actual = stock_actual + ?, total_cajas = total_cajas + ?, "
                + "titulo = COALESCE(?, titulo), editorial = COALESCE(?, editorial), autor = COALESCE(?, autor) "
                + "WHERE isbn = ?";
        try (PreparedStatement ps = conexion.prepareStatement(upd)) {
            ps.setInt(1, stockEntrante);
            ps.setInt(2, cajasEntrantes);
            ps.setString(3, titulo);
            ps.setString(4, editorial);
            ps.setString(5, autor);
            ps.setString(6, isbn);
            if (ps.executeUpdate() == 1) {
                return; // El libro existía (o era inactivo): reactivó, actualizó y sumó.
            }
        }
        // El libro no existía: insertamos el nuevo (NOT NULL: isbn, titulo, editorial deben estar).
        String ins = "INSERT INTO libros (isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conexion.prepareStatement(ins)) {
            ps.setString(1, isbn);
            ps.setString(2, titulo);
            ps.setString(3, editorial);
            ps.setString(4, autor);
            ps.setInt(5, stockEntrante);
            ps.setInt(6, cajasEntrantes);
            ps.setString(7, fechaCreacion);
            ps.executeUpdate();
        }
    }

    /**
     * Resta stock atómicamente (G1): UPDATE atómico con condición de stock suficiente:
     *   UPDATE libros SET stock_actual = stock_actual - ?, vendidos_acumulados = vendidos_acumulados + ?
     *   WHERE isbn = ? AND stock_actual >= ?
     * Devuelve true si se actualizó (executeUpdate() == 1), false si no había suficiente stock.
     */
    public boolean decrementarStockYRegistrarVenta(String isbn, int cantidad) throws SQLException {
        String sql = "UPDATE libros SET stock_actual = stock_actual - ?, " +
                "vendidos_acumulados = vendidos_acumulados + ? " +
                "WHERE isbn = ? AND activo = 1 AND stock_actual >= ?";
        try (PreparedStatement ps = conexion.prepareStatement(sql)) {
            ps.setInt(1, cantidad);
            ps.setInt(2, cantidad);
            ps.setString(3, isbn);
            ps.setInt(4, cantidad);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Actualiza un libro ya existente (textos de edición; activo mantiene 1).
     */
    public void actualizar(Libro libro) throws SQLException {
        String sql = "UPDATE libros SET titulo = ?, editorial = ?, autor = ?, stock_actual = ?, total_cajas = ? " +
                "WHERE isbn = ?";
        try (PreparedStatement ps = conexion.prepareStatement(sql)) {
            ps.setString(1, libro.getTitulo());
            ps.setString(2, libro.getEditorial());
            ps.setString(3, libro.getAutor());
            ps.setInt(4, libro.getStockActual());
            ps.setInt(5, libro.getTotalCajas());
            ps.setString(6, libro.getIsbn());
            ps.executeUpdate();
        }
    }

    /**
     * Borrado lógico (G6): marca el libro como inactivo (activo = 0).
     * Los movimientos se conservan en movimientos (no se borran físicamente).
     */
    public void marcarInactivo(String isbn) throws SQLException {
        String sql = "UPDATE libros SET activo = 0 WHERE isbn = ? AND activo = 1";
        try (PreparedStatement ps = conexion.prepareStatement(sql)) {
            ps.setString(1, isbn);
            ps.executeUpdate();
        }
    }

    /**
     * Lista libros activos con filtros por ISBN, título y editorial (búsqueda "contiene", case-insensitive)
     * y un orden: "desc" -> stock descendente (más stock primero); "asc" (o cualquier otro) ->
     * primero los libros con menos stock. Solo libros activos (activo = 1).
     */
    public List<Libro> listarFiltrado(String filtroIsbn, String filtroTitulo,
                                                   String filtroEditorial, String orden) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion, activo, vendidos_acumulados " +
                        "FROM libros WHERE activo = 1");
        List<Object> parametros = new ArrayList<>();

        if (!esVacio(filtroIsbn)) {
            sql.append(" AND isbn LIKE ?");
            parametros.add("%" + filtroIsbn + "%");
        }
        if (!esVacio(filtroTitulo)) {
            sql.append(" AND LOWER(titulo) LIKE ?");
            parametros.add("%" + filtroTitulo.toLowerCase() + "%");
        }
        if (!esVacio(filtroEditorial)) {
            sql.append(" AND LOWER(editorial) LIKE ?");
            parametros.add("%" + filtroEditorial.toLowerCase() + "%");
        }
        if ("desc".equals(orden)) {
            sql.append(" ORDER BY stock_actual DESC, isbn ASC");
        } else {
            sql.append(" ORDER BY stock_actual ASC, isbn ASC");
        }

        try (PreparedStatement ps = conexion.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setObject(i + 1, parametros.get(i));
            }
            List<Libro> resultado = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resultado.add(mapear(rs));
                }
            }
            return resultado;
        }
    }

    /**
     * Suma de las ventas acumuladas sobrevivientes al borrado (G6): se suma en
     * decrementarStockYRegistrarVenta, se conserva al marcarInactivo, y se mantiene
     * fija en la BD al eliminar el libro.
     */
    public int sumarVendidosAcumulados() throws SQLException {
        try (Statement st = conexion.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(vendidos_acumulados), 0) FROM libros")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * OPT1: sugerencias para el autocompletado con consulta acotada (LIKE + LIMIT).
     * Devuelve únicamente isbn, titulo y editorial de libros activos.
     */
    public List<Map<String, Object>> sugerirLibros(String consulta, int limite) throws SQLException {
        String q = consulta == null ? "" : consulta.trim();
        String patron = "%" + q.toLowerCase() + "%";
        String sql = "SELECT rowid, isbn, titulo, editorial, autor, stock_actual FROM libros WHERE activo = 1";
        if (!q.isEmpty()) {
            sql += " AND (isbn LIKE ? OR LOWER(titulo) LIKE ? OR LOWER(editorial) LIKE ?)";
        }
        sql += " ORDER BY titulo ASC, isbn ASC LIMIT ?";
        try (PreparedStatement ps = conexion.prepareStatement(sql)) {
            if (!q.isEmpty()) {
                ps.setString(1, patron);
                ps.setString(2, patron);
                ps.setString(3, patron);
                ps.setInt(4, limite);
            } else {
                ps.setInt(1, limite);
            }
            List<Map<String, Object>> resultado = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("id", rs.getLong("rowid"));
                    fila.put("isbn", rs.getString("isbn"));
                    fila.put("titulo", rs.getString("titulo"));
                    fila.put("editorial", rs.getString("editorial"));
                    fila.put("autor", rs.getString("autor"));
                    fila.put("stock_actual", rs.getInt("stock_actual"));
                    resultado.add(fila);
                }
            }
            return resultado;
        }
    }

    private Libro mapear(ResultSet rs) throws SQLException {
        Libro libro = new Libro();
        libro.setIsbn(rs.getString("isbn"));
        libro.setTitulo(rs.getString("titulo"));
        libro.setEditorial(rs.getString("editorial"));
        libro.setAutor(rs.getString("autor"));
        libro.setStockActual(rs.getInt("stock_actual"));
        libro.setTotalCajas(rs.getInt("total_cajas"));
        libro.setFechaCreacion(rs.getString("fecha_creacion"));
        libro.setPrecioVenta(rs.getObject("precio_venta", java.lang.Double.class));
        libro.setActivo(rs.getInt("activo"));
        libro.setVendidosAcumulados(rs.getInt("vendidos_acumulados"));
        return libro;
    }

    private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}

    public synchronized void actualizarPrecioVenta(String isbn, Double precioVenta) throws SQLException {
        try (PreparedStatement ps = conexion.prepareStatement("UPDATE libros SET precio_venta = ? WHERE isbn = ?")) {
            ps.setDouble(1, precioVenta); ps.setString(2, isbn); ps.executeUpdate();
        }
    }
