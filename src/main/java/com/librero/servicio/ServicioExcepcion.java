package com.librero.servicio;

/**
 * Excepción para errores esperados de negocio (validaciones del usuario).
 * El controlador las manda al cliente como un mensaje claro (código HTTP 400).
 */
public class ServicioExcepcion extends RuntimeException {

    public ServicioExcepcion(String message) {
        super(message);
    }
}
