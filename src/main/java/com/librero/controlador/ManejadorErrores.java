package com.librero.controlador;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.librero.dto.ErrorRespuesta;
import com.librero.servicio.NoEncontradoExcepcion;
import com.librero.servicio.ServicioExcepcion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Convierte las excepciones en respuestas JSON {"ok": false, "error": "..."}.
 * Los errores inesperados se registran en el log con su traza, pero al cliente solo
 * le llega un mensaje genérico.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(NoEncontradoExcepcion.class)
    public ResponseEntity<ErrorRespuesta> noEncontrado(NoEncontradoExcepcion e) {
        return respuesta(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ServicioExcepcion.class)
    public ResponseEntity<ErrorRespuesta> negocio(ServicioExcepcion e) {
        return respuesta(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuesta> validacion(MethodArgumentNotValidException e) {
        String mensaje = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(" "));
        return respuesta(HttpStatus.BAD_REQUEST, mensaje.isBlank() ? "Datos inválidos." : mensaje);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorRespuesta> jsonInvalido(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String campo = jme.getPath().get(jme.getPath().size() - 1).getFieldName();
            if (campo != null) {
                return respuesta(HttpStatus.BAD_REQUEST, "El campo '" + campo + "' tiene un formato inválido"
                        + (campo.equals("cantidad") || campo.equals("cajas") ? " (debe ser un número entero)." : "."));
            }
        }
        return respuesta(HttpStatus.BAD_REQUEST, "Cuerpo de la petición inválido; envíe JSON con los campos necesarios.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorRespuesta> tipoNoSoportado() {
        return respuesta(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Envíe los datos como application/json.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorRespuesta> metodoNoPermitido() {
        return respuesta(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorRespuesta> noExiste() {
        return respuesta(HttpStatus.NOT_FOUND, "No encontrado.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuesta> inesperado(Exception e) {
        log.error("Error no esperado al atender la petición", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno. Consulte al administrador.");
    }

    private static ResponseEntity<ErrorRespuesta> respuesta(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).body(ErrorRespuesta.de(mensaje));
    }
}
