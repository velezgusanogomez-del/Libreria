package com.librero.repositorio;

import com.librero.modelo.Movimiento;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Repositorio de acceso a la tabla movimientos (SQLite) usando JDBC.
 *
 * - insertar: guarda un movimiento (ENTRADA o SALIDA).
 * - listarConTitulo: lista los movimientos con el título del libro, del más reciente
 *   al más antiguo, con filtros por tipo, ISBN, fecha desde/hasta.
 */
public class MovimientoRepositorio {

    private final Connection conexion;

    public MovimientoRepositorio(Connection conexion) {
        this.conexion = conexion;
    }

    /** Guarda un movimiento en la tabla movimientos. */
    public long insertar(Movimiento m) throws SQLException {
        String sql = "INSERT INTO movimientos (isbn, tipo, cantidad, cajas, fecha, hora, nota) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conexion.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.getIsbn());
            ps.setString(2, m.getTipo());
            ps.setInt(3, m.getCantidad());
            ps.setObject(4, m.getCajas()); // NULL para salidas (cajas solo existen en entradas)
            ps.setString(5, m.getFecha());
            ps.setString(6, m.getHora());
            ps.setString(7, m.getNota());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Lista los movimientos con su título de libro, del más reciente al más antiguo
     * (fecha DESC, hora DESC, id DESC). Filtros: tipo (null/vacío/TODOS = todos),
     * isbn, desde/hasta (fechas YYYY-MM-DD).
     */
    public List<Map<String, Object>> listarConTitulo(String tipo, String isbn,
                                                                   String desde, String hasta) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.isbn, l.titulo, m.tipo, m.cantidad, m.cajas, m.fecha, m.hora, m.nota " +
                        "FROM movimientos m JOIN libros l ON l.isbn = m.isbn WHERE 1=1");
        List<Object> parametros = new ArrayList<>();

        if (!esVacio(tipo) && !"TODOS".equals(tipo)) {
            sql.append(" AND m.tipo = ?");
            parametros.add(tipo);
        }
        if (!esVacio(isbn)) {
            sql.append(" AND m.isbn = ?");
            parametros.add(isbn);
        }
        if (!esVacio(desde)) {
            sql.append(" AND m.fecha >= ?");
            parametros.add(desde);
        }
        if (!esVacio(hasta)) {
            sql.append(" AND m.fecha <= ?");
            parametros.add(hasta);
        }
        sql.append(" ORDER BY m.fecha DESC, m.hora DESC, m.id DESC");

        List<Map<String, Object>> resultado = new ArrayList<>();
        try (PreparedStatement ps = conexion.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setObject(i + 1, parametros.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> movimiento = new LinkedHashMap<>();
                    movimiento.put("id", rs.getLong("id"));
                    movimiento.put("isbn", rs.getString("isbn"));
                    movimiento.put("titulo", rs.getString("titulo"));
                    movimiento.put("tipo", rs.getString("tipo"));
                    movimiento.put("cantidad", rs.getInt("cantidad"));
                    movimiento.put("cajas", rs.getObject("cajas")); // null para salidas
                    movimiento.put("fecha", rs.getString("fecha"));
                    movimiento.put("hora", rs.getString("hora"));
                    movimiento.put("nota", rs.getString("nota"));
                    resultado.add(movimiento);
                }
            }
        }
        return resultado;
    }

    /**
     * M2: una sola consulta GROUP BY para tomar la última fecha de movimiento de cada
     * libro: SELECT isbn, MAX(fecha) FROM movimientos GROUP BY isbn. Devuelve mapa
     * ISBN -> fecha; libros sin movimientos no aparecen.
     */
    public Map<String, String> ultimasFechasDeMovimientos() throws SQLException {
        Map<String, String> mapa = new HashMap<>();
        try (Statement st = conexion.createStatement();
             ResultSet rs = st.executeQuery("SELECT isbn, MAX(fecha) FROM movimientos GROUP BY isbn")) {
            while (rs.next()) {
                String fecha = rs.getString(2);
                if (fecha != null && !fecha.isBlank()) {
                    mapa.put(rs.getString(1), fecha);
                }
            }
        }
        return mapa;
    }

        private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
