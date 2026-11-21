package com.librero.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Cuerpo de POST /api/llegada. Las reglas de negocio se validan en LibreroServicio. */
public record LlegadaPeticion(
        @Size(max = 40, message = "El ISBN admite como máximo 40 caracteres.") String isbn,
        @Size(max = 300, message = "El título admite como máximo 300 caracteres.") String titulo,
        @Size(max = 200, message = "La editorial admite como máximo 200 caracteres.") String editorial,
        @Size(max = 200, message = "El autor admite como máximo 200 caracteres.") String autor,
        Integer cantidad,
        Integer cajas,
        String fecha,
        String hora,
        @Size(max = 500, message = "La nota admite como máximo 500 caracteres.") String nota,
        @DecimalMin(value = "0", message = "El precio de venta no puede ser negativo.")
        @Digits(integer = 9, fraction = 2, message = "El precio de venta admite como máximo 2 decimales.")
        BigDecimal precioVenta) {
}
