package com.librero;

import com.google.gson.JsonObject;
import com.librero.repositorio.LibroRepositorio;
import com.librero.repositorio.MovimientoRepositorio;
import com.librero.servicio.LibreroServicer;
import com.librero.servicio.ServicioExcepcion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import com.librero.modelo.Libro;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de LibreroServicer (lógica de negocio):
 * altas, ventas, concurrencia, borrado lógico (G6) y validaciones.
 */
public class LibreroServicerTest {

    private Connection conexion;
    private LibroRepositorio libros;
    private MovimientoRepositorio movimientos;
    private LibreroServicer servicio;

    @BeforeEach
    void setUp() throws Exception {
        // Base de datos SQLite temporal.
        File archivo = File.createTempFile("librero-test-", ".db");
        archivo.deleteOnExit();
        conexion = DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
        // Aplica el esquema de la base de datos real (esquema.sql).
        LibreroApplication.aplicarSql(conexion, Files.readString(Paths.get("base-de-datos/esquema.sql"), StandardCharsets.UTF_8));
        LibreroApplication.migrarColumnas(conexion);
        libros = new LibroRepositorio(conexion);
        movimientos = new MovimientoRepositorio(conexion);
        servicio = new LibreroServicer(conexion, libros, movimientos, 5);
    }

    /* ------------------- altas ------------------- */

    @Test
    void testAltaNuevoLibro() throws Exception {
        Map<String, Object> r = servicio.registrarLlegada("ISBN-A", "Título A", "Editorial A", "Autor A", 5, 2, "2026-11-20", "10:00", "nota");
        assertEquals(true, r.get("ok"));
        assertTrue(String.valueOf(r.get("mensaje")).contains("Llegada registrada"));
        assertEquals("Título A", libros.buscarPorIden("ISBN-A").getTitulo());
        assertEquals("Editorial A", libros.buscarPorIden("ISBN-A").getEditorial());
        assertEquals("Autor A", libros.buscarPorIden("ISBN-A").getAutor());
        assertEquals(5, libros.buscarPorIden("ISBN-A").getStockActual());
        assertEquals(2, libros.buscarPorIden("ISBN-A").getTotalCajas());
        List<Map<String, Object>> mov = movimientos.listarConTitulo("ENTRADA", "ISBN-A", null, null);
        assertEquals(1, mov.size());
        assertEquals("ENTRADA", mov.get(0).get("tipo"));
        assertEquals(5, mov.get(0).get("cantidad"));
        assertEquals(2, mov.get(0).get("cajas"));
    }

    @Test
    void testAltaExistenteSumaStockYCajes() throws Exception {
        servicio.registrarLlegada("ISBN-A", "Título A", "Editorial A", "Autor A", 5, 2, "2026-11-20", "10:00", null);
        Map<String, Object> r = servicio.registrarLlegada("ISBN-A", "Título A", "Editorial A", "Autor A", 3, 1, "2026-11-20", "11:00", null);
        assertEquals(true, r.get("ok"));
        assertEquals(8, libros.buscarPorIden("ISBN-A").getStockActual());
        assertEquals(3, libros.buscarPorIden("ISBN-A").getTotalCajas());
        assertEquals(2, movimientos.listarConTitulo("ENTRADA", "ISBN-A", null, null).size());
    }

    @Test
    void testLlegadaSinTituloOEditorial() {
        // Alta de libro nuevo sin título: error.
        assertThrows(ServicioExcepcion.class,
                () -> servicio.registrarLlegada("ISBN-SIN-TIT", "", "Editorial S", null, 1, 1, "2026-11-20", "10:00", null));
    }

    /* ------------------- ventas ------------------- */

    @Test
    void testVentaStockSuficiente() throws Exception {
        servicio.registrarLlegada("ISBN-A", "Título A", "Editorial A", null, 5, 2, "2026-11-20", "10:00", null);
        Map<String, Object> r = servicio.registrarSalida("ISBN-A", 3, "2026-11-20", "11:00", "venta");
        assertEquals(true, r.get("ok"));
        assertEquals(2, libros.buscarPorIden("ISBN-A").getStockActual());
        assertEquals(3, libros.buscarPorIden("ISBN-A").getVendidosAcumulados());
        List<Map<String, Object>> mov = movimientos.listarConTitulo("SALIDA", "ISBN-A", null, null);
        assertEquals(1, mov.size());
        assertEquals(3, mov.get(0).get("cantidad"));
        assertEquals("venta", mov.get(0).get("nota"));
        assertNull(mov.get(0).get("cajas")); // las salidas no tienen cajas
    }

    @Test
    void testVentaStockInsuficienteNoModifica() throws Exception {
        servicio.registrarLlegada("ISBN-A", "Título A", "Editorial A", null, 5, 2, "2026-11-20", "10:00", null);
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarSalida("ISBN-A", 10, "2026-11-20", "11:00", null));
        assertEquals(5, libros.buscarPorIden("ISBN-A").getStockActual());
        assertEquals(0, movimientos.listarConTitulo("SALIDA", "ISBN-A", null, null).size());
    }

    @Test
    void testVentaISBNNoRegistrado() {
        ServicioExcepcion e = assertThrows(ServicioExcepcion.class,
                () -> servicio.registrarSalida("ISBN-NOPE", 1, "2026-11-20", "10:00", null));
        assertTrue(e.getMessage().contains("no está registrado"));
    }

    /* ------------------- concurrencia ------------------- */

    @Test
    void testConcurrenciaAlta6StockFinal6() throws Exception {
        // 6 altas concurrentes para un ISBN nuevo: stock final debe ser 6, no 2.
        String isbn = "ISBN-CONC-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            pool.execute(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                try {
                    servicio.registrarLlegada(isbn, "Título conc", "Editorial conc", null, 1, 1, "2026-11-20", "10:00", null);
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        com.librero.modelo.Libro libro = libros.buscarPorIden(isbn);
        assertNotNull(libro);
        assertEquals(6, libro.getStockActual());
        assertEquals(6, libro.getTotalCajas());
        assertEquals(6, movimientos.listarConTitulo("ENTRADA", isbn, null, null).size());
    }

    @Test
    void testConcurrenciaVentasUnaSoloExitosa() throws Exception {
        // Stock de 1: con 10 ventas concurrentes, solo 1 debe tener éxito.
        servicio.registrarLlegada("ISBN-VT", "Título VT", "Editorial VT", null, 1, 1, "2026-11-20", "10:00", null);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger exitosas = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                try {
                    servicio.registrarSalida("ISBN-VT", 1, "2026-11-20", "11:00", null);
                    exitosas.incrementAndGet();
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, exitosas.get());
        assertEquals(0, libros.buscarPorIden("ISBN-VT").getStockActual());
    }

    /* ------------------- borrado (G6) ------------------- */

    @Test
    void testBorradoMarcaInactivoYConservaVentas() throws Exception {
        // (G6): no se borran físicamente los movimientos al eliminar un libro.
        servicio.registrarLlegada("ISBN-D", "Título D", "Editorial D", null, 5, 2, "2026-11-20", "10:00", null);
        Map<String, Object> v = servicio.registrarSalida("ISBN-D", 2, "2026-11-20", "11:00", null);
        assertEquals(true, v.get("ok"));
        assertEquals(2, ((Number) servicio.dashboard().get("total_libros_vendidos")).intValue());

        Map<String, Object> r = servicio.eliminarLibro("ISBN-D");
        assertEquals(true, r.get("ok"));

        // No aparece en el inventario.
        assertTrue(servicio.inventario().stream().noneMatch(l -> "ISBN-D".equals(l.get("isbn"))));
        // El libro se marcó activo=0.
        assertNotNull(libros.buscarPorIden("ISBN-D"));
        assertFalse(libros.buscarPorIden("ISBN-D").isActivo());
        // Los movimientos se conservan.
        assertEquals(2, movimientos.listarConTitulo(null, "ISBN-D", null, null).size());

        // Simulamos un reinicio: nuevos repositorios + servicio sobre la misma conexión.
        LibroRepositorio libros2 = new LibroRepositorio(conexion);
        MovimientoRepositorio movimientos2 = new MovimientoRepositorio(conexion);
        LibreroServicer servicio2 = new LibreroServicer(conexion, libros2, movimientos2, 5);
        assertTrue(servicio2.inventario().stream().noneMatch(l -> "ISBN-D".equals(l.get("isbn"))));
        assertEquals(2, ((Number) servicio2.dashboard().get("total_libros_vendidos")).intValue());
        // No se puede editar un libro eliminado.
        assertThrows(ServicioExcepcion.class, () -> servicio2.actualizarLibro("ISBN-D", "Nuevo", null, null));
    }

    /* ------------------- validaciones ------------------- */

    private void assertDecimalRejected(Object valor) {
        JsonObject j = new JsonObject();
        if (valor instanceof Number) {
            j.addProperty("cantidad", (Double) valor);
        } else {
            j.addProperty("cantidad", String.valueOf(valor));
        }
        assertThrows(ServicioExcepcion.class, () -> LibreroServicer.enteroDe(j, "cantidad", -1),
                "esperaba rechazo para el valor: " + valor);
    }

    @Test
    void testDecimalesRechazados() {
        // M1: cantidades decimales son rechazadas, no truncadas.
        assertDecimalRejected(1.5);
        assertDecimalRejected(2.9);
        assertDecimalRejected("abc");
        // Valores enteros válidos.
        JsonObject ok5 = new JsonObject();
        ok5.addProperty("cantidad", 5.0);
        assertEquals(5, LibreroServicer.enteroDe(ok5, "cantidad", -1));
        JsonObject ok7 = new JsonObject();
        ok7.addProperty("cantidad", 7);
        assertEquals(7, LibreroServicer.enteroDe(ok7, "cantidad", -1));
        // Campo ausente: devuelve el default.
        JsonObject vacio = new JsonObject();
        assertEquals(-1, LibreroServicer.enteroDe(vacio, "cantidad", -1));
    }

    @Test
    void testHorasInvalidasRechazadas() throws Exception {
        // O4: horas válidas (10:30) se aceptan.
        servicio.registrarLlegada("I-H", "T", "E", null, 1, 1, "2026-11-20", "10:30", null);
        // Horas inválidas (25:61, "10:30:15", vacío) son rechazadas.
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada("I-H2", "T", "E", null, 1, 1, "2026-11-20", "25:61", null));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada("I-H3", "T", "E", null, 1, 1, "2026-11-20", "10:30:15", null));
        // Hora vacía: ahora permitida (hora opcional)
    }

    @Test
    void testFechasInvalidasRechazadas() throws Exception {
        // M6/O4: fechas inválidas son rechazadas.
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada("I-F1", "T", "E", null, 1, 1, "2026-13-40", "10:00", null));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarLlegada("I-F2", "T", "E", null, 1, 1, "abc", "10:00", null));
        assertThrows(ServicioExcepcion.class, () -> servicio.registrarSalida("I-F3", 1, "2026-02-30", "10:00", null));
        // Fecha válida OK (2026-02-28).
        servicio.registrarLlegada("I-F4", "T", "E", null, 1, 1, "2026-02-28", "10:00", null);
    }

    @Test
    void testHistorialFiltrosInvalidos() throws Exception {
        // M6: validamos los filtros de fecha del historial.
        ServicioExcepcion e1 = assertThrows(ServicioExcepcion.class, () -> servicio.historial(null, null, "abc", null));
        assertTrue(e1.getMessage().contains("Formato de fecha inválido"));
        ServicioExcepcion e2 = assertThrows(ServicioExcepcion.class, () -> servicio.historial(null, null, "2026-11-20", "2026-11-19"));
        assertTrue(e2.getMessage().contains("'desde' no puede ser mayor que 'hasta'"));
        // Rango válido (desde == hasta) devuelve movimientos del día.
        servicio.registrarLlegada("I-HST", "T", "E", null, 1, 1, "2026-11-20", "10:00", null);
        List<Map<String, Object>> lista = servicio.historial(null, null, "2026-11-20", "2026-11-20");
        assertEquals(1, lista.size());
    }

    @Test
    void testCSVFormulasNeutralizadas() throws Exception {
        // M3: valores que empiezan con =, +, - o @ se neutralizan en el CSV.
        servicio.registrarLlegada("I-CSV", "=1+1", "=HYPERLINK(\"http://x\")", null, 1, 1, "2026-11-20", "10:00", null);
        String csv = servicio.inventarioCSV();
        assertTrue(csv.contains("'=1+1"), "el título debería neutralizarse: " + csv);
    }

    @Test
    void testEditarAutorVacíoLimpia() throws Exception {
        // M4: "no enviado" mantiene el autor; "enviado vacío" lo borra.
        servicio.registrarLlegada("I-E", "Título E", "Editorial E", "Autor Test", 1, 1, "2026-11-20", "10:00", null);
        assertEquals("Autor Test", libros.buscarPorIden("I-E").getAutor());
        Map<String, Object> r = servicio.actualizarLibro("I-E", "Título Nuevo", "Editorial Nueva", "");
        assertEquals("Título Nuevo", r.get("titulo"));
        assertEquals("Editorial Nueva", r.get("editorial"));
        assertNull(r.get("autor")); // M4: enviado vacío => se borra
        assertNull(libros.buscarPorIden("I-E").getAutor());
        // No enviado: se mantiene.
        Map<String, Object> r2 = servicio.actualizarLibro("I-E", null, null, null);
        assertEquals("Título Nuevo", r2.get("titulo"));
    }

    @Test
    void testUlpimasFechasDeMovimientos() throws Exception {
        // M2: una sola consulta GROUP BY para tomar las últimas fechas.
        servicio.registrarLlegada("I-M", "Título M", "Editorial M", null, 1, 1, "2026-11-20", "10:00", null);
        Map<String, String> ultimas = movimientos.ultimasFechasDeMovimientos();
        assertEquals("2026-11-20", ultimas.get("I-M"));
        // Inventario usa la misma estrategia.
        List<Map<String, Object>> inv = servicio.inventario();
        assertEquals(1, inv.size());
        assertEquals("2026-11-20", inv.get(0).get("ultima_fecha_movimiento"));
    }

    @Test
    void testDashboardTotales() throws Exception {
        servicio.registrarLlegada("I-D", "Título D", "Editorial D", null, 5, 2, "2026-11-20", "10:00", null);
        servicio.registrarSalida("I-D", 4, "2026-11-20", "11:00", null);
        Map<String, Object> dash = servicio.dashboard();
        assertEquals(1, dash.get("total_libros_diferentes"));
        assertEquals(1, dash.get("total_stock"));
        assertEquals(2, dash.get("total_cajas_arribados"));
        assertEquals(4, dash.get("total_libros_vendidos"));
    }

    @Test
    void testReactivacionLlegadaInactivo() throws Exception {
        // R1: una llegada a un libro inactivo (eliminado) reactiva activo=1 y suma stock/cajas.
        Map<String, Object> a = servicio.registrarLlegada("ISBN-RA", "Título RA", "Editorial RA", null, 5, 2, "2026-11-20", "10:00", null);
        assertEquals(true, a.get("ok"));
        libros.marcarInactivo("ISBN-RA");
        assertFalse(libros.buscarPorIden("ISBN-RA").isActivo());

        // Buscar lo excluye de inventario tras la eliminación.
        assertTrue(servicio.inventario().stream().noneMatch(l -> "ISBN-RA".equals(l.get("isbn"))));

        // Nueva llegada con el mismo ISBN reactiva.
        Map<String, Object> r = servicio.registrarLlegada("ISBN-RA", "Título RA actualizado", "Editorial RA2", "Autor RA", 3, 1, "2026-11-20", "11:00", null);
        assertEquals(true, r.get("ok"));
        Libro libro = libros.buscarPorIden("ISBN-RA");
        assertTrue(libro.isActivo());
        assertEquals(8, libro.getStockActual());
        assertEquals(3, libro.getTotalCajas());
        assertEquals("Título RA actualizado", libro.getTitulo());
        assertEquals("Editorial RA2", libro.getEditorial());
        assertEquals("Autor RA", libro.getAutor());
        // Aparece en inventario nuevamente.
        assertTrue(servicio.inventario().stream().anyMatch(l -> "ISBN-RA".equals(l.get("isbn"))));
    }

    @Test
    void testBuscarLibroNoExiste() {
        ServicioExcepcion e = assertThrows(ServicioExcepcion.class, () -> servicio.buscarLibro("NOPE"));
        assertTrue(e.getMessage().contains("no está registrado"));
    }
}
