package com.librero.dto;

/** Datos para el pie de página: IP local y puerto, para entrar desde otra computadora. */
public record InfoServidor(String ip, int port, String app, String usuario) {
}
