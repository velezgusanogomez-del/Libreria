package com.librero.dto;

import java.math.BigDecimal;

/** Libro tal como lo recibe la interfaz web (JSON en snake_case). */
public record LibroVista(
        String isbn,
        String titulo,
        String editorial,
        String autor,
        int stockActual,
        int totalCajas,
        String fechaCreacion,
        BigDecimal precioVenta,
        int vendidosAcumulados,
        String ultimaFechaMovimiento,
        boolean pocoStock) {
}
