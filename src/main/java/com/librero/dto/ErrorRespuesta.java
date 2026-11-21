package com.librero.dto;

/** Respuesta de error de la API: {"ok": false, "error": "..."}. */
public record ErrorRespuesta(boolean ok, String error) {

    public static ErrorRespuesta de(String mensaje) {
        return new ErrorRespuesta(false, mensaje);
    }
}
