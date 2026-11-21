package com.librero.dto;

/** Movimiento con el título del libro, para el historial. */
public record MovimientoVista(
        long id,
        String isbn,
        String titulo,
        String tipo,
        int cantidad,
        Integer cajas,
        String fecha,
        String hora,
        String nota) {
}
