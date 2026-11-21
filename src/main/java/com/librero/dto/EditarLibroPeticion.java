package com.librero.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Cuerpo de PUT /api/inventario/{isbn}.
 * Campo null = no se cambia. Autor "" = se borra el autor. Precio null = no se cambia.
 */
public record EditarLibroPeticion(
        @Size(max = 300, message = "El título admite como máximo 300 caracteres.") String titulo,
        @Size(max = 200, message = "La editorial admite como máximo 200 caracteres.") String editorial,
        @Size(max = 200, message = "El autor admite como máximo 200 caracteres.") String autor,
        @DecimalMin(value = "0", message = "El precio de venta no puede ser negativo.")
        @Digits(integer = 9, fraction = 2, message = "El precio de venta admite como máximo 2 decimales.")
        BigDecimal precioVenta) {
}
