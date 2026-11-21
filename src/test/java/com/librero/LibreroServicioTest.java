package com.librero;

import com.librero.dto.EditarLibroPeticion;
import com.librero.dto.LibroVista;
import com.librero.dto.LlegadaPeticion;
import com.librero.dto.MovimientoVista;
import com.librero.dto.Panel;
import com.librero.dto.ResultadoOperacion;
import com.librero.dto.SalidaPeticion;
import com.librero.modelo.Libro;
import com.librero.modelo.TipoMovimiento;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;
import com.librero.servicio.LibreroServicio;
import com.librero.servicio.NoEncontradoExcepcion;
import com.librero.servicio.ServicioExcepcion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Lógica de negocio: llegadas, ventas, concurrencia, borrado lógico, precios y validaciones. */
class LibreroServicioTest extends PruebaBase {

    @Autowired LibreroServicio servicio;
    @Autowired LibroRepositorio libros;
    @Autowired MovimientoRepositorio movimientos;

    private static LlegadaPeticion llegada(String isbn, String titulo, String editorial, String autor,
                                           Integer cantidad, Integer cajas, String fecha, String hora) {
        return new LlegadaPeticion(isbn, titulo, editorial, autor, cantidad, cajas, fecha, hora, null, null);
    }

    private ResultadoOperacion llegar(String isbn, int cantidad, int cajas) {
        return servicio.registrarLlegada(llegada(isbn, "Título " + isbn, "Editorial " + isbn, null,
                cantidad, cajas, "2026-11-20", "10:00"));
    }

    private ResultadoOperacion vender(String isbn, int cantidad) {
        return servicio.registrarSalida(new SalidaPeticion(isbn, cantidad, "2026-11-20", "11:00", null));
    }

    private Libro libro(String isbn) {
        return libros.buscar(isbn).orElseThrow();
    }

    /* ------------------- llegadas ------------------- */

    @Test
    void altaDeLibroNuevo() {
        ResultadoOperacion r = servicio.registrarLlegada(new LlegadaPeticion("ISBN-A", "Título A", "Editorial A",
                "Autor A", 5, 2, "2026-11-20", "10:00", "nota", null));
        assertTrue(r.ok());
        assertTrue(r.mensaje().contains("Llegada registrada"));
        Libro l = libro("ISBN-A");
        assertEquals("Título A", l.titulo());
        assertEquals("Autor A", l.autor());
        assertEquals(5, l.stockActual());
        assertEquals(2, l.totalCajas());
        List<MovimientoVista> mov = movimientos.listar(TipoMovimiento.ENTRADA, "ISBN-A", null, null);
        assertEquals(1, mov.size());
        assertEquals(5, mov.get(0).cantidad());
        assertEquals(2, mov.get(0).cajas());
        assertEquals(r.idMovimiento(), mov.get(0).id());
    }

    @Test
    void llegadaAExistenteSumaStockYCajas() {
        llegar("ISBN-A", 5, 2);
        llegar("ISBN-A", 3, 1);
        assertEquals(8, libro("ISBN-A").stockActual());
        assertEquals(3, libro("ISBN-A").totalCajas());
        assertEquals(2, movimientos.listar(TipoMovimiento.ENTRADA, "ISBN-A", null, null).size());
    }

    @Test
    void libroNuevoSinTituloSeRechazaSinGuardarNada() {
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(
                llegada("ISBN-X", "", "Editorial", null, 1, 1, "2026-11-20", "10:00")));
        assertTrue(libros.buscar("ISBN-X").isEmpty());
        assertTrue(movimientos.listar(null, "ISBN-X", null, null).isEmpty());
    }

    @Test
    void cantidadesInvalidasSeRechazan() {
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I", "T", "E", null, 0, 1, "2026-11-20", null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I", "T", "E", null, null, 1, "2026-11-20", null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I", "T", "E", null, 1, -2, "2026-11-20", null)));
    }

    /* ------------------- ventas ------------------- */

    @Test
    void ventaConStockSuficiente() {
        llegar("ISBN-A", 5, 2);
        ResultadoOperacion r = servicio.registrarSalida(new SalidaPeticion("ISBN-A", 3, "2026-11-20", "11:00", "venta"));
        assertTrue(r.ok());
        assertEquals(2, libro("ISBN-A").stockActual());
        assertEquals(3, libro("ISBN-A").vendidosAcumulados());
        List<MovimientoVista> mov = movimientos.listar(TipoMovimiento.SALIDA, "ISBN-A", null, null);
        assertEquals(1, mov.size());
        assertEquals("venta", mov.get(0).nota());
        assertNull(mov.get(0).cajas());
    }

    @Test
    void ventaSinStockSuficienteNoCambiaNada() {
        llegar("ISBN-A", 5, 2);
        ServicioExcepcion e = assertThrows(ServicioExcepcion.class, () -> vender("ISBN-A", 10));
        assertTrue(e.getMessage().contains("No hay suficientes libros"));
        assertEquals(5, libro("ISBN-A").stockActual());
        assertTrue(movimientos.listar(TipoMovimiento.SALIDA, "ISBN-A", null, null).isEmpty());
    }

    @Test
    void ventaDeIsbnNoRegistrado() {
        NoEncontradoExcepcion e = assertThrows(NoEncontradoExcepcion.class, () -> vender("ISBN-NOPE", 1));
        assertTrue(e.getMessage().contains("no está registrado"));
    }

    /* ------------------- concurrencia ------------------- */

    @Test
    void seisLlegadasSimultaneasSumanSeis() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch inicio = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            pool.execute(() -> {
                try {
                    inicio.await();
                    llegar("ISBN-CONC", 1, 1);
                } catch (Exception ignorada) {
                    // se verifica el resultado final
                }
            });
        }
        inicio.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(6, libro("ISBN-CONC").stockActual());
        assertEquals(6, movimientos.listar(TipoMovimiento.ENTRADA, "ISBN-CONC", null, null).size());
    }

    @Test
    void diezVentasSimultaneasConStockUnoSoloUnaExitosa() throws Exception {
        llegar("ISBN-VT", 1, 1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch inicio = new CountDownLatch(1);
        AtomicInteger exitosas = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                try {
                    inicio.await();
                    vender("ISBN-VT", 1);
                    exitosas.incrementAndGet();
                } catch (Exception ignorada) {
                    // las demás deben fallar
                }
            });
        }
        inicio.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, exitosas.get());
        assertEquals(0, libro("ISBN-VT").stockActual());
    }

    /* ------------------- borrado lógico ------------------- */

    @Test
    void eliminarMarcaInactivoYConservaVentas() {
        llegar("ISBN-D", 5, 2);
        vender("ISBN-D", 2);
        assertTrue(servicio.eliminarLibro("ISBN-D").ok());

        assertTrue(servicio.inventario().stream().noneMatch(l -> "ISBN-D".equals(l.isbn())));
        assertFalse(libro("ISBN-D").activo());
        assertEquals(2, movimientos.listar(null, "ISBN-D", null, null).size());
        assertEquals(2, servicio.panel().totalLibrosVendidos());
        assertThrows(NoEncontradoExcepcion.class,
                () -> servicio.actualizarLibro("ISBN-D", new EditarLibroPeticion("Nuevo", null, null, null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.eliminarLibro("ISBN-D"));
    }

    @Test
    void llegadaReactivaLibroEliminado() {
        llegar("ISBN-RA", 5, 2);
        servicio.eliminarLibro("ISBN-RA");
        servicio.registrarLlegada(llegada("ISBN-RA", "Título nuevo", "Editorial 2", "Autor", 3, 1, "2026-11-20", "11:00"));
        Libro l = libro("ISBN-RA");
        assertTrue(l.activo());
        assertEquals(8, l.stockActual());
        assertEquals("Título nuevo", l.titulo());
        assertTrue(servicio.inventario().stream().anyMatch(v -> "ISBN-RA".equals(v.isbn())));
    }

    /* ------------------- validaciones ------------------- */

    @Test
    void horasInvalidasSeRechazan() {
        servicio.registrarLlegada(llegada("I-H", "T", "E", null, 1, 1, "2026-11-20", "10:30"));
        servicio.registrarLlegada(llegada("I-H0", "T", "E", null, 1, 1, "2026-11-20", null));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I-H2", "T", "E", null, 1, 1, "2026-11-20", "25:61")));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I-H3", "T", "E", null, 1, 1, "2026-11-20", "10:30:15")));
    }

    @Test
    void fechasInvalidasSeRechazan() {
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I-F1", "T", "E", null, 1, 1, "2026-13-40", null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I-F2", "T", "E", null, 1, 1, "abc", null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada(llegada("I-F3", "T", "E", null, 1, 1, "", null)));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarSalida(new SalidaPeticion("I-F3", 1, "2026-02-30", null, null)));
        servicio.registrarLlegada(llegada("I-F4", "T", "E", null, 1, 1, "2026-02-28", null));
    }

    @Test
    void filtrosDeHistorial() {
        ServicioExcepcion e1 = assertThrows(ServicioExcepcion.class, () -> servicio.historial(null, null, "abc", null));
        assertTrue(e1.getMessage().contains("Formato de fecha inválido"));
        ServicioExcepcion e2 = assertThrows(ServicioExcepcion.class, () -> servicio.historial(null, null, "2026-11-20", "2026-11-19"));
        assertTrue(e2.getMessage().contains("'desde' no puede ser mayor que 'hasta'"));
        assertThrows(ServicioExcepcion.class, () -> servicio.historial("OTRO", null, null, null));
        llegar("I-HST", 1, 1);
        assertEquals(1, servicio.historial("TODOS", null, "2026-11-20", "2026-11-20").size());
        assertEquals(0, servicio.historial("SALIDA", null, null, null).size());
    }

    @Test
    void busquedaConComodinesNoLosInterpreta() {
        llegar("ISBN-1", 1, 1);
        assertTrue(servicio.inventario(null, "%", null, null).isEmpty());
        assertEquals(1, servicio.inventario(null, "título isbn-1", null, null).size());
    }

    /* ------------------- edición y precios ------------------- */

    @Test
    void editarAutorVacioLoBorraYNuloLoMantiene() {
        servicio.registrarLlegada(llegada("I-E", "Título E", "Editorial E", "Autor Test", 1, 1, "2026-11-20", null));
        LibroVista r = servicio.actualizarLibro("I-E", new EditarLibroPeticion("Título Nuevo", "Editorial Nueva", "", null));
        assertEquals("Título Nuevo", r.titulo());
        assertNull(r.autor());
        LibroVista r2 = servicio.actualizarLibro("I-E", new EditarLibroPeticion(null, null, null, null));
        assertEquals("Título Nuevo", r2.titulo());
    }

    @Test
    void precioSeGuardaEnCentavosSinErroresDeRedondeo() {
        servicio.registrarLlegada(new LlegadaPeticion("I-P", "T", "E", null, 10, 1, "2026-11-20", null, null,
                new BigDecimal("0.10")));
        assertEquals(10L, libro("I-P").precioVentaCentavos());
        ResultadoOperacion venta = vender("I-P", 3);
        assertTrue(venta.mensaje().contains("$0.30"), venta.mensaje());

        LibroVista editado = servicio.actualizarLibro("I-P", new EditarLibroPeticion(null, null, null, new BigDecimal("150.5")));
        assertEquals(new BigDecimal("150.50"), editado.precioVenta());
        assertThrows(ServicioExcepcion.class, () -> servicio.actualizarLibro("I-P",
                new EditarLibroPeticion(null, null, null, new BigDecimal("1.999"))));
        assertThrows(ServicioExcepcion.class, () -> servicio.actualizarLibro("I-P",
                new EditarLibroPeticion(null, null, null, new BigDecimal("-1"))));
    }

    @Test
    void fichaIncluyeUltimaLlegadaYVenta() {
        llegar("I-FI", 5, 2);
        vender("I-FI", 2);
        var ficha = servicio.ficha("I-FI");
        assertEquals("I-FI", ficha.libro().isbn());
        assertEquals(5, ficha.ultimaEntrada().cantidad());
        assertEquals(2, ficha.ultimaSalida().cantidad());
        assertThrows(NoEncontradoExcepcion.class, () -> servicio.ficha("NOPE"));
    }

    /* ------------------- panel y CSV ------------------- */

    @Test
    void panelTotales() {
        llegar("I-D", 5, 2);
        vender("I-D", 4);
        Panel p = servicio.panel();
        assertEquals(1, p.totalLibrosDiferentes());
        assertEquals(1, p.totalStock());
        assertEquals(2, p.totalCajasArribados());
        assertEquals(4, p.totalLibrosVendidos());
        assertEquals(1, p.librosConPocoStock().size());
        assertEquals("2026-11-20", p.librosConPocoStock().get(0).ultimaFechaMovimiento());
    }

    @Test
    void csvNeutralizaFormulas() {
        servicio.registrarLlegada(llegada("I-CSV", "=1+1", "=HYPERLINK(\"http://x\")", null, 1, 1, "2026-11-20", null));
        String csv = servicio.inventarioCsv();
        assertTrue(csv.contains("'=1+1"), csv);
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""), csv);
    }
}
