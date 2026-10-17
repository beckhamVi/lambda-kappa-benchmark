package com.bi.ingestionlambda.batch;

import com.bi.ingestionlambda.domain.RawEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class EventCsvReader implements AutoCloseable {

    private final BufferedReader reader;
    private long lineNumber;

    public EventCsvReader(Path path) throws IOException {
        this.reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8), 1 << 16);
        reader.readLine(); // salta la cabecera
        this.lineNumber = 1;
    }

    /** Devuelve la siguiente línea o null al terminar el archivo. */
    public RawEvent next() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        lineNumber++;
        return new RawEvent(lineNumber, line);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}