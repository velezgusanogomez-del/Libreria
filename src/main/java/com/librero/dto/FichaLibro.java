package com.librero.dto;

/** Ficha de consulta: datos del libro + su última llegada y su última venta. */
public record FichaLibro(LibroVista libro, MovimientoVista ultimaEntrada, MovimientoVista ultimaSalida) {
}
