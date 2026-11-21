package com.librero.servicio;

/** El recurso pedido no existe (o el libro está eliminado). El controlador responde 404. */
public class NoEncontradoExcepcion extends ServicioExcepcion {

    public NoEncontradoExcepcion(String message) {
        super(message);
    }
}
