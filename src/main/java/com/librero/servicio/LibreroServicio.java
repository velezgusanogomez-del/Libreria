package com.librero.servicio;

import com.librero.dto.EditarLibroPeticion;
import com.librero.dto.FichaLibro;
import com.librero.dto.LibroVista;
import com.librero.dto.LlegadaPeticion;
import com.librero.dto.MovimientoVista;
import com.librero.dto.Panel;
import com.librero.dto.ResultadoOperacion;
import com.librero.dto.SalidaPeticion;
import com.librero.modelo.Libro;
import com.librero.modelo.Movimiento;
import com.librero.modelo.TipoMovimiento;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lógica de negocio del inventario.
 *
 * Las operaciones que modifican stock son {@link Transactional}: el cambio en {@code libros}
 * y el movimiento se guardan juntos o no se guarda nada. La resta de stock es un UPDATE
 * condicional, así que el stock nunca queda negativo aunque lleguen ventas simultáneas.
 */
@Service
public class LibreroServicio {

    private static final Pattern PATRON_HORA = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final int LIMITE_SUGERENCIAS = 12;

    private final LibroRepositorio libros;
    private final MovimientoRepositorio movimientos;
    private final int umbralPocoStock;

    public LibreroServicio(LibroRepositorio libros, MovimientoRepositorio movimientos,
                           @Value("${librero.umbral-poco-stock:5}") int umbralPocoStock) {
        this.libros = libros;
        this.movimientos = movimientos;
        this.umbralPocoStock = umbralPocoStock;
    }

    /* ------------------------------------------------------------------ */
    /* Llegadas y salidas                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Registra una llegada: crea el libro si el ISBN es nuevo, o suma stock y cajas si ya
     * existe (reactivándolo si estaba eliminado). Guarda un movimiento ENTRADA.
     */
    @Transactional
    public ResultadoOperacion registrarLlegada(LlegadaPeticion p) {
        String isbn = obligatorio(p.isbn(), "El ISBN es obligatorio.");
        validarFechaYHora(p.fecha(), p.hora());
        int cantidad = positivo(p.cantidad(), "La cantidad de libros que llegaron debe ser mayor que 0.");
        int cajas = positivo(p.cajas(), "La cantidad de cajas que llegaron debe ser mayor que 0.");
        Long precio = aCentavos(p.precioVenta());

        String titulo = textoONulo(p.titulo());
        String editorial = textoONulo(p.editorial());
        String autor = textoONulo(p.autor());

        boolean existia = libros.sumarLlegada(isbn, titulo, editorial, autor, cantidad, cajas, precio);
        if (!existia) {
            obligatorio(titulo, "El título del libro es obligatorio al registrar la llegada.");
            obligatorio(editorial, "La editorial del libro es obligatoria al registrar la llegada.");
            libros.insertar(isbn, titulo, editorial, autor, cantidad, cajas, LocalDate.now().toString(), precio);
        }
        long id = movimientos.insertar(new Movimiento(isbn, TipoMovimiento.ENTRADA, cantidad, cajas,
                p.fecha().trim(), textoONulo(p.hora()), textoONulo(p.nota())));

        Libro libro = libroExistente(isbn);
        String mensaje = "Llegada registrada: " + libro.titulo() + " - stock actual: " + libro.stockActual()
                + " libros, cajas totales: " + libro.totalCajas() + ".";
        return new ResultadoOperacion(true, mensaje, id, vista(libro, p.fecha().trim()));
    }

    /** Registra una venta/salida. Rechaza la operación si no hay stock suficiente. */
    @Transactional
    public ResultadoOperacion registrarSalida(SalidaPeticion p) {
        String isbn = obligatorio(p.isbn(), "El ISBN es obligatorio.");
        validarFechaYHora(p.fecha(), p.hora());
        int cantidad = positivo(p.cantidad(), "La cantidad de libros que salieron debe ser mayor que 0.");

        Libro antes = libros.buscar(isbn).filter(Libro::activo).orElseThrow(() -> new NoEncontradoExcepcion(
                "El libro con ISBN '" + isbn + "' no está registrado. "
                        + "Registre primero la llegada del libro en \"Registrar llegada\"."));
        if (!libros.descontarStock(isbn, cantidad)) {
            throw new ServicioExcepcion("No hay suficientes libros en stock: hay " + antes.stockActual()
                    + " y se intentan sacar " + cantidad + ". La venta no fue registrada.");
        }
        long id = movimientos.insertar(new Movimiento(isbn, TipoMovimiento.SALIDA, cantidad, null,
                p.fecha().trim(), textoONulo(p.hora()), textoONulo(p.nota())));

        Libro libro = libroExistente(isbn);
        StringBuilder mensaje = new StringBuilder("Venta registrada: " + cantidad + " libros de '" + libro.titulo() + "'");
        if (libro.precioVentaCentavos() != null) {
            mensaje.append(" (total: $").append(deCentavos(libro.precioVentaCentavos() * cantidad).toPlainString()).append(")");
        }
        mensaje.append(". Stock restante: ").append(libro.stockActual()).append(".");
        return new ResultadoOperacion(true, mensaje.toString(), id, vista(libro, p.fecha().trim()));
    }

    /* ------------------------------------------------------------------ */
    /* Consulta y edición de libros                                        */
    /* ------------------------------------------------------------------ */

    public List<LibroVista> inventario(String filtroIsbn, String filtroTitulo, String filtroEditorial, String orden) {
        Map<String, String> ultimas = movimientos.ultimasFechas();
        return libros.listarActivos(filtroIsbn, filtroTitulo, filtroEditorial, orden).stream()
                .map(l -> vista(l, ultimas.get(l.isbn())))
                .toList();
    }

    public List<LibroVista> inventario() {
        return inventario(null, null, null, "asc");
    }

    public LibroVista buscarLibro(String isbn) {
        Libro libro = libroActivo(isbn);
        return vista(libro, movimientos.ultimasFechas().get(libro.isbn()));
    }

    public FichaLibro ficha(String isbn) {
        Libro libro = libroActivo(isbn);
        return new FichaLibro(
                vista(libro, movimientos.ultimasFechas().get(libro.isbn())),
                movimientos.ultimo(TipoMovimiento.ENTRADA, libro.isbn()).orElse(null),
                movimientos.ultimo(TipoMovimiento.SALIDA, libro.isbn()).orElse(null));
    }

    public List<LibroVista> sugerirLibros(String consulta) {
        return libros.sugerir(consulta, LIMITE_SUGERENCIAS).stream().map(l -> vista(l, null)).toList();
    }

    /**
     * Edita título, editorial, autor y/o precio. Un campo null no se cambia; un autor
     * vacío ("") borra el autor.
     */
    @Transactional
    public LibroVista actualizarLibro(String isbn, EditarLibroPeticion p) {
        Libro libro = libroActivo(isbn);
        String titulo = estaVacio(p.titulo()) ? libro.titulo() : p.titulo().trim();
        String editorial = estaVacio(p.editorial()) ? libro.editorial() : p.editorial().trim();
        String autor = p.autor() == null ? libro.autor() : textoONulo(p.autor());
        Long precio = p.precioVenta() == null ? libro.precioVentaCentavos() : aCentavos(p.precioVenta());
        libros.actualizarDatos(libro.isbn(), titulo, editorial, autor, precio);
        return buscarLibro(libro.isbn());
    }

    /** Borrado lógico: el libro deja de verse, pero su historial y sus ventas se conservan. */
    @Transactional
    public ResultadoOperacion eliminarLibro(String isbn) {
        String clave = obligatorio(isbn, "El ISBN es obligatorio.");
        Libro libro = libros.buscar(clave).orElseThrow(() ->
                new NoEncontradoExcepcion("El libro con ISBN '" + clave + "' no está registrado."));
        if (!libro.activo()) {
            throw new ServicioExcepcion("El libro con ISBN '" + clave + "' ya está eliminado.");
        }
        libros.marcarInactivo(libro.isbn());
        return new ResultadoOperacion(true, "Libro eliminado: " + libro.titulo() + " (ISBN " + libro.isbn()
                + "). El histórico de movimientos se conserva.", null, null);
    }

    /* ------------------------------------------------------------------ */
    /* Historial, panel y exportación                                      */
    /* ------------------------------------------------------------------ */

    public List<MovimientoVista> historial(String tipo, String isbn, String desde, String hasta) {
        validarFecha(desde);
        validarFecha(hasta);
        if (!estaVacio(desde) && !estaVacio(hasta) && desde.trim().compareTo(hasta.trim()) > 0) {
            throw new ServicioExcepcion("'desde' no puede ser mayor que 'hasta'.");
        }
        return movimientos.listar(tipoDe(tipo), isbn, desde, hasta);
    }

    public Panel panel() {
        List<LibroVista> activos = inventario();
        int totalStock = activos.stream().mapToInt(LibroVista::stockActual).sum();
        int totalCajas = activos.stream().mapToInt(LibroVista::totalCajas).sum();
        List<LibroVista> pocoStock = activos.stream().filter(LibroVista::pocoStock).toList();
        return new Panel(activos.size(), totalStock, totalCajas, libros.sumarVendidos(), pocoStock, umbralPocoStock);
    }

    public String inventarioCsv() {
        StringBuilder sb = new StringBuilder("isbn,titulo,editorial,autor,stock_actual,total_cajas,precio_venta,ultima_fecha_movimiento\n");
        for (LibroVista l : inventario()) {
            fila(sb, l.isbn(), l.titulo(), l.editorial(), l.autor(), l.stockActual(), l.totalCajas(),
                    l.precioVenta() == null ? null : l.precioVenta().toPlainString(), l.ultimaFechaMovimiento());
        }
        return sb.toString();
    }

    public String movimientosCsv() {
        StringBuilder sb = new StringBuilder("isbn,titulo,tipo,cantidad,cajas,fecha,hora,nota\n");
        for (MovimientoVista m : historial(null, null, null, null)) {
            fila(sb, m.isbn(), m.titulo(), m.tipo(), m.cantidad(), m.cajas(), m.fecha(), m.hora(), m.nota());
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Ayudantes                                                           */
    /* ------------------------------------------------------------------ */

    private Libro libroActivo(String isbn) {
        String clave = obligatorio(isbn, "El ISBN es obligatorio.");
        return libros.buscar(clave).filter(Libro::activo).orElseThrow(() -> new NoEncontradoExcepcion(
                "El libro con ISBN '" + clave + "' no está registrado. Registre primero la llegada del libro."));
    }

    private Libro libroExistente(String isbn) {
        return libros.buscar(isbn).orElseThrow(() -> new IllegalStateException("Libro desaparecido: " + isbn));
    }

    private LibroVista vista(Libro l, String ultimaFecha) {
        return new LibroVista(l.isbn(), l.titulo(), l.editorial(), l.autor(), l.stockActual(), l.totalCajas(),
                l.fechaCreacion(), deCentavos(l.precioVentaCentavos()), l.vendidosAcumulados(),
                ultimaFecha == null ? "" : ultimaFecha, l.stockActual() < umbralPocoStock);
    }

    private static TipoMovimiento tipoDe(String tipo) {
        if (estaVacio(tipo) || "TODOS".equalsIgnoreCase(tipo.trim())) {
            return null;
        }
        try {
            return TipoMovimiento.valueOf(tipo.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServicioExcepcion("Tipo de movimiento inválido; use ENTRADA, SALIDA o TODOS.");
        }
    }

    /** Convierte un precio (máximo 2 decimales, no negativo) a centavos. */
    static Long aCentavos(BigDecimal precio) {
        if (precio == null) {
            return null;
        }
        if (precio.signum() < 0) {
            throw new ServicioExcepcion("El precio de venta no puede ser negativo.");
        }
        if (precio.stripTrailingZeros().scale() > 2) {
            throw new ServicioExcepcion("El precio de venta admite como máximo 2 decimales.");
        }
        return precio.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    static BigDecimal deCentavos(Long centavos) {
        return centavos == null ? null : BigDecimal.valueOf(centavos, 2);
    }

    private static String obligatorio(String valor, String mensaje) {
        if (estaVacio(valor)) {
            throw new ServicioExcepcion(mensaje);
        }
        return valor.trim();
    }

    private static int positivo(Integer valor, String mensaje) {
        if (valor == null || valor < 1) {
            throw new ServicioExcepcion(mensaje);
        }
        return valor;
    }

    private static void validarFechaYHora(String fecha, String hora) {
        if (estaVacio(fecha)) {
            throw new ServicioExcepcion("La fecha es obligatoria; la hora es opcional.");
        }
        validarFecha(fecha);
        validarHora(hora);
    }

    private static void validarFecha(String fecha) {
        if (estaVacio(fecha)) {
            return;
        }
        try {
            LocalDate.parse(fecha.trim());
        } catch (DateTimeParseException e) {
            throw new ServicioExcepcion("Formato de fecha inválido; use YYYY-MM-DD.");
        }
    }

    private static void validarHora(String hora) {
        if (estaVacio(hora)) {
            return;
        }
        String h = hora.trim();
        try {
            if (!PATRON_HORA.matcher(h).matches()) {
                throw new DateTimeParseException("formato", h, 0);
            }
            LocalTime.parse(h);
        } catch (DateTimeParseException e) {
            throw new ServicioExcepcion("Formato de hora inválido; use HH:MM.");
        }
    }

    private static boolean estaVacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String textoONulo(String valor) {
        return estaVacio(valor) ? null : valor.trim();
    }

    private static void fila(StringBuilder sb, Object... valores) {
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(valores[i]));
        }
        sb.append('\n');
    }

    /** Escapa un valor CSV y neutraliza fórmulas de Excel/LibreOffice (=, +, -, @). */
    static String csv(Object valor) {
        if (valor == null) {
            return "";
        }
        String s = String.valueOf(valor);
        if (!(valor instanceof Number) && !s.isEmpty() && "+-=@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        s = s.replace("\"", "\"\"");
        boolean comillas = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        return comillas ? "\"" + s + "\"" : s;
    }
}
