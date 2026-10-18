package com.bi.apigrpc.domain;

/** La peticion es invalida (parametros fuera de rango, valores desconocidos...). Se traduce a HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
