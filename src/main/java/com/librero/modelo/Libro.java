package com.librero.modelo;

/**
 * Libro del inventario tal como está guardado en la tabla {@code libros}.
 *
 * @param stockActual         libros disponibles en este momento
 * @param totalCajas          cajas recibidas en total (histórico)
 * @param activo              false = borrado lógico (no aparece en el inventario)
 * @param vendidosAcumulados  ventas totales; se conservan aunque el libro se elimine
 * @param precioVentaCentavos precio en centavos, o null si no tiene precio
 */
public record Libro(
        String isbn,
        String titulo,
        String editorial,
        String autor,
        int stockActual,
        int totalCajas,
        String fechaCreacion,
        boolean activo,
        int vendidosAcumulados,
        Long precioVentaCentavos) {
}
