package com.librero.repositorio;

import com.librero.modelo.Libro;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a la tabla {@code libros}. Todas las consultas usan parámetros (sin concatenar
 * datos del usuario en el SQL). Las transacciones las delimita LibreroServicio.
 */
@Repository
public class LibroRepositorio {

    private static final String COLUMNAS =
            "isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion, "
                    + "activo, vendidos_acumulados, precio_venta_centavos";

    private static final RowMapper<Libro> MAPEADOR = (rs, i) -> new Libro(
            rs.getString("isbn"),
            rs.getString("titulo"),
            rs.getString("editorial"),
            rs.getString("autor"),
            rs.getInt("stock_actual"),
            rs.getInt("total_cajas"),
            rs.getString("fecha_creacion"),
            rs.getInt("activo") == 1,
            rs.getInt("vendidos_acumulados"),
            rs.getObject("precio_venta_centavos") == null ? null : rs.getLong("precio_venta_centavos"));

    private final JdbcTemplate jdbc;

    public LibroRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Busca un libro (activo o no) por ISBN. */
    public Optional<Libro> buscar(String isbn) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM libros WHERE isbn = ?", MAPEADOR, isbn)
                .stream().findFirst();
    }

    /**
     * Suma una llegada a un libro existente (y lo reactiva si estaba eliminado).
     * Los textos y el precio solo se cambian si vienen informados (COALESCE).
     *
     * @return true si el libro existía
     */
    public boolean sumarLlegada(String isbn, String titulo, String editorial, String autor,
                                int cantidad, int cajas, Long precioCentavos) {
        return jdbc.update("""
                UPDATE libros
                   SET activo = 1,
                       stock_actual = stock_actual + ?,
                       total_cajas = total_cajas + ?,
                       titulo = COALESCE(?, titulo),
                       editorial = COALESCE(?, editorial),
                       autor = COALESCE(?, autor),
                       precio_venta_centavos = COALESCE(?, precio_venta_centavos)
                 WHERE isbn = ?""",
                cantidad, cajas, titulo, editorial, autor, precioCentavos, isbn) == 1;
    }

    public void insertar(String isbn, String titulo, String editorial, String autor,
                         int cantidad, int cajas, String fechaCreacion, Long precioCentavos) {
        jdbc.update("""
                INSERT INTO libros (isbn, titulo, editorial, autor, stock_actual, total_cajas,
                                    fecha_creacion, precio_venta_centavos)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                isbn, titulo, editorial, autor, cantidad, cajas, fechaCreacion, precioCentavos);
    }

    /**
     * Resta stock de forma atómica, solo si alcanza (nunca queda negativo).
     *
     * @return true si se descontó; false si no había stock suficiente
     */
    public boolean descontarStock(String isbn, int cantidad) {
        return jdbc.update("""
                UPDATE libros
                   SET stock_actual = stock_actual - ?,
                       vendidos_acumulados = vendidos_acumulados + ?
                 WHERE isbn = ? AND activo = 1 AND stock_actual >= ?""",
                cantidad, cantidad, isbn, cantidad) == 1;
    }

    /** Actualiza los datos editables de un libro. */
    public void actualizarDatos(String isbn, String titulo, String editorial, String autor, Long precioCentavos) {
        jdbc.update("UPDATE libros SET titulo = ?, editorial = ?, autor = ?, precio_venta_centavos = ? WHERE isbn = ?",
                titulo, editorial, autor, precioCentavos, isbn);
    }

    /** Borrado lógico: el libro deja de verse, pero sus movimientos y ventas se conservan. */
    public void marcarInactivo(String isbn) {
        jdbc.update("UPDATE libros SET activo = 0 WHERE isbn = ? AND activo = 1", isbn);
    }

    /**
     * Libros activos filtrados por ISBN / título / editorial ("contiene", sin distinguir
     * mayúsculas). Orden "desc" = más stock primero; cualquier otro valor = menos stock primero.
     */
    public List<Libro> listarActivos(String filtroIsbn, String filtroTitulo, String filtroEditorial, String orden) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNAS + " FROM libros WHERE activo = 1");
        List<Object> parametros = new ArrayList<>();
        agregarFiltro(sql, parametros, "isbn", filtroIsbn);
        agregarFiltro(sql, parametros, "titulo", filtroTitulo);
        agregarFiltro(sql, parametros, "editorial", filtroEditorial);
        sql.append("desc".equalsIgnoreCase(orden)
                ? " ORDER BY stock_actual DESC, isbn ASC"
                : " ORDER BY stock_actual ASC, isbn ASC");
        return jdbc.query(sql.toString(), MAPEADOR, parametros.toArray());
    }

    /** Autocompletado: búsqueda acotada por ISBN, título o editorial. */
    public List<Libro> sugerir(String consulta, int limite) {
        String q = consulta == null ? "" : consulta.trim();
        if (q.isEmpty()) {
            return jdbc.query("SELECT " + COLUMNAS + " FROM libros WHERE activo = 1 "
                    + "ORDER BY titulo ASC, isbn ASC LIMIT ?", MAPEADOR, limite);
        }
        String patron = patronLike(q);
        return jdbc.query("SELECT " + COLUMNAS + " FROM libros WHERE activo = 1 "
                        + "AND (isbn LIKE ? ESCAPE '\\' OR titulo LIKE ? ESCAPE '\\' OR editorial LIKE ? ESCAPE '\\') "
                        + "ORDER BY titulo ASC, isbn ASC LIMIT ?",
                MAPEADOR, patron, patron, patron, limite);
    }

    /** Total de libros vendidos, incluidos los de libros eliminados. */
    public long sumarVendidos() {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(vendidos_acumulados), 0) FROM libros", Long.class);
        return total == null ? 0 : total;
    }

    private static void agregarFiltro(StringBuilder sql, List<Object> parametros, String columna, String valor) {
        if (valor != null && !valor.isBlank()) {
            // LIKE de SQLite ya ignora mayúsculas/minúsculas en letras ASCII.
            sql.append(" AND ").append(columna).append(" LIKE ? ESCAPE '\\'");
            parametros.add(patronLike(valor.trim()));
        }
    }

    /** "%texto%" escapando los comodines % y _ que escriba el usuario. */
    private static String patronLike(String texto) {
        String escapado = texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escapado + "%";
    }
}
