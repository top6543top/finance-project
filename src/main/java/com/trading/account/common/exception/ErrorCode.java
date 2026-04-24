package com.trading.account.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /**
     * 400 Bad Request
     */
    INVALID_INPUT_VALUE("40000", HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INVALID_REQUEST_BODY("40001", HttpStatus.BAD_REQUEST, "요청 본문이 올바르지 않습니다."),
    INVALID_METHOD_ARGUMENT("40002", HttpStatus.BAD_REQUEST, "메서드 인자가 올바르지 않습니다."),
    MISSING_REQUEST_PARAMETER("40003", HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
    ARGUMENT_TYPE_MISMATCH("40004", HttpStatus.BAD_REQUEST, "요청 파라미터 타입이 올바르지 않습니다."),
    UNSUPPORTED_MEDIA_TYPE("40005", HttpStatus.BAD_REQUEST, "지원하지 않는 미디어 타입입니다."),

    /**
     * 401 Unauthorized
     */
    UNAUTHORIZED("40100", HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FAILURE_LOGIN("40101", HttpStatus.UNAUTHORIZED, "로그인에 실패했습니다."),
    TOKEN_EXPIRED("40102", HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    TOKEN_UNSUPPORTED("40103", HttpStatus.UNAUTHORIZED, "지원되지 않는 형식의 토큰입니다."),
    TOKEN_MALFORMED("40104", HttpStatus.UNAUTHORIZED, "잘못된 토큰입니다."),
    TOKEN_TYPE("40105", HttpStatus.UNAUTHORIZED, "잘못된 토큰 타입입니다."),
    INVALID_PASSWORD("40106", HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),

    /**
     * 403 Forbidden
     */
    ACCESS_DENIED("40300", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    EMPTY_AUTHENTICATION("40301", HttpStatus.FORBIDDEN, "인증 토큰이 비어있습니다."),
    INVALID_ROLE("40302", HttpStatus.FORBIDDEN, "권한이 맞지 않습니다."),

    /**
     * 404 Not Found
     */
    NOT_FOUND_END_POINT("40400", HttpStatus.NOT_FOUND, "존재하지 않는 엔드포인트입니다."),
    ENTITY_NOT_FOUND("40401", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    NOT_FOUND_USER("40402", HttpStatus.NOT_FOUND, "해당 사용자가 존재하지 않습니다."),

    /**
     * 405 Method Not Allowed
     */
    METHOD_NOT_ALLOWED("40500", HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    /**
     * 409 Conflict
     */
    CONFLICT_USER("40900", HttpStatus.CONFLICT, "이미 존재하는 사용자입니다."),

    /**
     * 500 Internal Server Error
     */
    INTERNAL_SERVER_ERROR("50000", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    EXTERNAL_SERVER_ERROR("50001", HttpStatus.INTERNAL_SERVER_ERROR, "외부 서버 오류가 발생했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
