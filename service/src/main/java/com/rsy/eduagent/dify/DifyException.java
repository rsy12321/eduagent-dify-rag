package com.rsy.eduagent.dify;

public final class DifyException extends RuntimeException {
    private final String code;
    private final int status;

    public DifyException(String code, String message, int status) {
        super(message); // Never retain an upstream exception/body: it may contain credentials.
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
    public static DifyException timeout() {
        return new DifyException("DIFY_TIMEOUT", "Dify request timed out", 504);
    }
    public static DifyException protocol() {
        return new DifyException("DIFY_PROTOCOL_ERROR", "Dify returned an invalid or incomplete response", 502);
    }
    public static DifyException disconnected() {
        return new DifyException("CLIENT_DISCONNECTED", "Client disconnected before completion", 499);
    }
}
