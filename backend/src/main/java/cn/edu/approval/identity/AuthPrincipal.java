package cn.edu.approval.identity;

/** Request-local identity only; services must re-read the user and session before using it. */
public record AuthPrincipal(
    String userId,
    String sessionId,
    String status,
    boolean mustChangePassword,
    String clientType) {}
