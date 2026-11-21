package com.librero.dto;

/** Respuesta de una operación que cambia datos (llegada, salida, eliminación). */
public record ResultadoOperacion(boolean ok, String mensaje, Long idMovimiento, LibroVista libro) {
}
