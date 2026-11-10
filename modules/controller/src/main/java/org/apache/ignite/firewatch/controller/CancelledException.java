package org.apache.ignite.firewatch.controller;

/** The job was cancelled from the UI (or the controller is shutting down) while a step was running. */
public final class CancelledException extends RuntimeException {
    /**
     * Creates the exception.
     *
     * @param msg Exception message.
     */
    public CancelledException(String msg) {
        super(msg);
    }
}
