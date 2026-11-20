package dev.dineshrama.relay;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static ApiException conflict(String code, String message) { return new ApiException(409,code,message); }
    public static ApiException bad(String message) { return new ApiException(400,"INVALID_REQUEST",message); }
}
