package com.librero.modelo;

/**
 * Movimiento de stock (tabla {@code movimientos}).
 *
 * @param tipo  ENTRADA (llegada) o SALIDA (venta)
 * @param cajas solo las entradas tienen cajas; null en salidas
 * @param hora  HH:MM, opcional
 */
public record Movimiento(
        String isbn,
        TipoMovimiento tipo,
        int cantidad,
        Integer cajas,
        String fecha,
        String hora,
        String nota) {
}
