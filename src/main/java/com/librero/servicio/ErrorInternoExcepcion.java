package com.librero.servicio;

/** Excepción para fallos internos (base de datos, etc.). Controlador devuelve HTTP 500 genérico. */
public class ErrorInternoExcepcion extends RuntimeException {

    public ErrorInternoExcepcion(String message) {
        super(message);
    }

    public ErrorInternoExcepcion(String message, Throwable cause) {
        super(message, cause);
    }
}
