package com.librero.dto;

import jakarta.validation.constraints.Size;

/** Cuerpo de POST /api/salida. */
public record SalidaPeticion(
        @Size(max = 40, message = "El ISBN admite como máximo 40 caracteres.") String isbn,
        Integer cantidad,
        String fecha,
        String hora,
        @Size(max = 500, message = "La nota admite como máximo 500 caracteres.") String nota) {
}
