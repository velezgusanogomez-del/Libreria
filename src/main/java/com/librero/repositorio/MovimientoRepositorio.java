package com.librero.repositorio;

import com.librero.dto.MovimientoVista;
import com.librero.modelo.Movimiento;
import com.librero.modelo.TipoMovimiento;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Acceso a la tabla {@code movimientos} (historial de llegadas y ventas). */
@Repository
public class MovimientoRepositorio {

    private static final String SELECT_CON_TITULO =
            "SELECT m.id, m.isbn, l.titulo, m.tipo, m.cantidad, m.cajas, m.fecha, m.hora, m.nota "
                    + "FROM movimientos m JOIN libros l ON l.isbn = m.isbn WHERE 1 = 1";

    private static final String ORDEN = " ORDER BY m.fecha DESC, m.hora DESC, m.id DESC";

    private static final RowMapper<MovimientoVista> MAPEADOR = (rs, i) -> new MovimientoVista(
            rs.getLong("id"),
            rs.getString("isbn"),
            rs.getString("titulo"),
            rs.getString("tipo"),
            rs.getInt("cantidad"),
            rs.getObject("cajas") == null ? null : rs.getInt("cajas"),
            rs.getString("fecha"),
            rs.getString("hora"),
            rs.getString("nota"));

    private final JdbcTemplate jdbc;

    public MovimientoRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Guarda un movimiento y devuelve su id. Debe llamarse dentro de una transacción para
     * que last_insert_rowid() se lea en la misma conexión.
     */
    public long insertar(Movimiento m) {
        jdbc.update("INSERT INTO movimientos (isbn, tipo, cantidad, cajas, fecha, hora, nota) VALUES (?, ?, ?, ?, ?, ?, ?)",
                m.isbn(), m.tipo().name(), m.cantidad(), m.cajas(), m.fecha(), m.hora(), m.nota());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    /**
     * Movimientos del más reciente al más antiguo. Filtros opcionales: tipo (ENTRADA/SALIDA;
     * vacío o TODOS = ambos), ISBN exacto y rango de fechas YYYY-MM-DD.
     */
    public List<MovimientoVista> listar(TipoMovimiento tipo, String isbn, String desde, String hasta) {
        StringBuilder sql = new StringBuilder(SELECT_CON_TITULO);
        List<Object> parametros = new ArrayList<>();
        if (tipo != null) {
            sql.append(" AND m.tipo = ?");
            parametros.add(tipo.name());
        }
        if (isbn != null && !isbn.isBlank()) {
            sql.append(" AND m.isbn = ?");
            parametros.add(isbn.trim());
        }
        if (desde != null && !desde.isBlank()) {
            sql.append(" AND m.fecha >= ?");
            parametros.add(desde.trim());
        }
        if (hasta != null && !hasta.isBlank()) {
            sql.append(" AND m.fecha <= ?");
            parametros.add(hasta.trim());
        }
        sql.append(ORDEN);
        return jdbc.query(sql.toString(), MAPEADOR, parametros.toArray());
    }

    /** Último movimiento de un tipo para un libro (para la ficha). */
    public Optional<MovimientoVista> ultimo(TipoMovimiento tipo, String isbn) {
        return jdbc.query(SELECT_CON_TITULO + " AND m.tipo = ? AND m.isbn = ?" + ORDEN + " LIMIT 1",
                MAPEADOR, tipo.name(), isbn).stream().findFirst();
    }

    /** Última fecha de movimiento de cada libro, en una sola consulta: ISBN -> fecha. */
    public Map<String, String> ultimasFechas() {
        Map<String, String> mapa = new HashMap<>();
        jdbc.query("SELECT isbn, MAX(fecha) AS ultima FROM movimientos GROUP BY isbn", rs -> {
            String fecha = rs.getString("ultima");
            if (fecha != null && !fecha.isBlank()) {
                mapa.put(rs.getString("isbn"), fecha);
            }
        });
        return mapa;
    }
}
