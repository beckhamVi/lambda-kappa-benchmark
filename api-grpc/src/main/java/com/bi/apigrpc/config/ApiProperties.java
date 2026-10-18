package com.bi.apigrpc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de la API (prefijo "api" en application.properties).
 *
 * @param maxRangeDays maximo de dias que puede abarcar una consulta
 * @param maxLimit     maximo de filas que puede pedir un top-N
 */
@ConfigurationProperties(prefix = "api")
public record ApiProperties(
        @DefaultValue("366") int maxRangeDays,
        @DefaultValue("100") int maxLimit) {
}
