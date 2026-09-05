package com.tontiflow.interfaces.rest;

import com.tontiflow.application.exception.AccountDisabledException;
import com.tontiflow.application.exception.AccountLockedException;
import com.tontiflow.application.exception.AccountNotFoundException;
import com.tontiflow.application.exception.AccountNotFoundInAdminException;
import com.tontiflow.application.exception.DuplicateEmailException;
import com.tontiflow.application.exception.DuplicatePermissionNameException;
import com.tontiflow.application.exception.DuplicateRoleNameException;
import com.tontiflow.application.exception.InvalidCredentialsException;
import com.tontiflow.application.exception.InvalidRefreshTokenException;
import com.tontiflow.application.exception.PermissionAlreadyAssignedException;
import com.tontiflow.application.exception.PermissionNotFoundException;
import com.tontiflow.application.exception.RefreshTokenExpiredException;
import com.tontiflow.application.exception.RefreshTokenReuseDetectedException;
import com.tontiflow.application.exception.RoleAlreadyAssignedException;
import com.tontiflow.application.exception.RoleNotAssignedException;
import com.tontiflow.application.exception.RoleNotFoundException;
import com.tontiflow.core.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mappe les exceptions du domaine credentials/authentification vers des
 * réponses HTTP uniformes ({@link ErrorResponse}, RFC 7807), sans jamais
 * exposer de donnée sensible (mot de passe, hash, JWT, clé privée,
 * stacktrace).
 *
 * <p>{@link AccountNotFoundException} et {@link InvalidCredentialsException}
 * produisent volontairement le même message générique — empêcher
 * l'énumération de comptes existants à partir des réponses d'erreur.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String GENERIC_INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";
    private static final String GENERIC_INVALID_REFRESH_TOKEN_MESSAGE = "Invalid refresh token";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, GENERIC_INVALID_CREDENTIALS_MESSAGE, request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, GENERIC_INVALID_CREDENTIALS_MESSAGE, request);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(HttpServletRequest request) {
        return build(HttpStatus.LOCKED, "Account locked", request);
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<ErrorResponse> handleAccountDisabled(HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Account disabled", request);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Email already in use", request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, GENERIC_INVALID_REFRESH_TOKEN_MESSAGE, request);
    }

    @ExceptionHandler(RefreshTokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenExpired(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, GENERIC_INVALID_REFRESH_TOKEN_MESSAGE, request);
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenReuseDetected(HttpServletRequest request) {
        // Meme message generique que les deux autres cas : ne jamais laisser l'appelant
        // distinguer "token inconnu" / "expire" / "reutilise" (empeche toute reconnaissance
        // de l'etat interne du token par un tiers).
        return build(HttpStatus.UNAUTHORIZED, GENERIC_INVALID_REFRESH_TOKEN_MESSAGE, request);
    }

    @ExceptionHandler(AccountNotFoundInAdminException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFoundInAdmin(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Account not found", request);
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFound(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Role not found", request);
    }

    @ExceptionHandler(PermissionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePermissionNotFound(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Permission not found", request);
    }

    @ExceptionHandler(DuplicateRoleNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRoleName(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Role name already in use", request);
    }

    @ExceptionHandler(DuplicatePermissionNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicatePermissionName(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Permission name already in use", request);
    }

    @ExceptionHandler(RoleAlreadyAssignedException.class)
    public ResponseEntity<ErrorResponse> handleRoleAlreadyAssigned(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Role already assigned", request);
    }

    @ExceptionHandler(PermissionAlreadyAssignedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionAlreadyAssigned(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Permission already assigned", request);
    }

    @ExceptionHandler(RoleNotAssignedException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotAssigned(HttpServletRequest request) {
        // Traite comme une ressource introuvable (l'association demandee n'existe pas),
        // coherent avec RoleNotFoundException/PermissionNotFoundException ci-dessus.
        return build(HttpStatus.NOT_FOUND, "Role not assigned to this account", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // Uniquement le nom des champs invalides et le message de contrainte :
        // jamais la valeur fournie par le client (peut contenir un mot de passe).
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return build(HttpStatus.BAD_REQUEST, detail, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String detail, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                status.getReasonPhrase(),
                status.value(),
                detail,
                request.getRequestURI(),
                resolveCorrelationId(request)
        );
        return ResponseEntity.status(status).body(body);
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_ID_HEADER);
        return (header == null || header.isBlank()) ? UUID.randomUUID().toString() : header;
    }
}
