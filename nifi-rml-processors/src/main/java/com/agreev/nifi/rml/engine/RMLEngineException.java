package com.agreev.nifi.rml.engine;

public class RMLEngineException extends Exception {

    public RMLEngineException(String message) {
        super(message);
    }

    public RMLEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
