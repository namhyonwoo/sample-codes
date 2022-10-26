package com.wordvice.experteditingv0.config.servlet;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

public class LoggingFilter extends OncePerRequestFilter {

    protected static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    protected static final List<MediaType> VISIBLE_TYPES = Arrays.asList(
            MediaType.APPLICATION_JSON,
            MediaType.valueOf("text/*"),
            MediaType.APPLICATION_FORM_URLENCODED,
            MediaType.APPLICATION_JSON,
            MediaType.APPLICATION_XML,
            MediaType.valueOf("application/*+json"),
            MediaType.valueOf("application/*+xml")
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString());
        if (isAsyncDispatch(request)) {
            filterChain.doFilter(request, response);
        } else {
            doFilterWrapped(request, response, filterChain);
        }
        MDC.clear();
    }

    protected void doFilterWrapped(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest wrapRequest = wrapRequest(request);
        ContentCachingResponseWrapper wrapResponse = wrapResponse(response);
        try {
            printRequestLog(createRequestLog(wrapRequest));
            filterChain.doFilter(wrapRequest, wrapResponse); //다음 대상으로 요청을 전달
        } finally {
            printResponseLog(createResponseLog(wrapResponse));
            wrapResponse.copyBodyToResponse();
        }
    }

    private MediaType getMediaType(String contentType) {
        if (contentType == null) return null;
        return MediaType.valueOf(contentType);
    }

    private static boolean isVisible(MediaType mediaType) {
        if (mediaType == null) return false;
        return VISIBLE_TYPES.stream().anyMatch(visibleType -> visibleType.includes(mediaType));
    }

    private static void printResponseLog(HttpLog responseLogData) {
        log.info("Response : status={}, headers={}, content-type={}, body={}", responseLogData.getStatus(), responseLogData.getHeaders(), responseLogData.getContentType(), responseLogData.getRawBody());
    }

    private static void printRequestLog(HttpLog requestLogData) {
        log.info("Request : method={}, uri={}, headers={}, content-type={}, body={}", requestLogData.getMethod(), requestLogData.getUri(), requestLogData.getHeaders(), requestLogData.getContentType(), requestLogData.getRawBody());
    }

    private HttpLog createRequestLog(HttpServletRequest request) {
        return HttpLog.builder()
                .method(request.getMethod())
                .contentType(request.getContentType())
                .uri(getUri(request))
                .headers(getRequestHeaders(request))
                .rawBody(getRequestContentString(request))
                .build();
    }

    private static String getUri(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (StringUtils.hasText(queryString)) {
            return request.getRequestURL().append("?").append(queryString).toString();
        } else {
            return request.getRequestURL().toString();
        }
    }

    private String getRequestContentString(HttpServletRequest request) {
        try {
            MediaType mediaType = getMediaType(request.getContentType());
            if (isVisible(mediaType)) {
                byte[] contentAsByteArray = request.getInputStream().readAllBytes();
                return getContentString(contentAsByteArray, mediaType, request.getCharacterEncoding());
            } else if (mediaType == null) {
                return null;
            } else {
                return "Binary Content";
            }
        } catch (IOException e) {
            log.error("IOException = {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Collection<String>> getRequestHeaders(HttpServletRequest request) {
        Map<String, Collection<String>> headerMap = new HashMap<>();
        Collections.list(request.getHeaderNames()).forEach(headerName -> headerMap.put(headerName, Collections.list(request.getHeaders(headerName))));
        return headerMap;
    }

    private HttpLog createResponseLog(ContentCachingResponseWrapper response) {
        return HttpLog.builder()
                .status(response.getStatus())
                .contentType(response.getContentType())
                .headers(getResponseHeaders(response))
                .rawBody(getResponseContentString(response))
                .build();
    }

    private String getResponseContentString(ContentCachingResponseWrapper response) {
        MediaType mediaType = getMediaType(response.getContentType());
        if (isVisible(mediaType)) {
            byte[] contentAsByteArray = response.getContentAsByteArray();
            return getContentString(contentAsByteArray, mediaType, response.getCharacterEncoding());
        }
        if (mediaType == null) {
            return null;
        } else {
            return "Binary Content";
        }
    }

    private static Map<String, Collection<String>> getResponseHeaders(HttpServletResponse response) {
        Map<String, Collection<String>> headerMap = new HashMap<>();
        response.getHeaderNames().forEach(headerName -> headerMap.put(headerName, response.getHeaders(headerName)));
        return headerMap;
    }

    private String getContentString(byte[] contentAsByteArray, MediaType mediaType, String contentEncoding) {
        String text = null;
        try {
            text = new String(contentAsByteArray, contentEncoding);
            if (mediaType == MediaType.APPLICATION_JSON) {
                return new JSONObject(text).toString();
            }
            return text;
        } catch (UnsupportedEncodingException e) {
            log.info("UnsupportedEncodingException: {}", e.getMessage());
            return null;
        } catch (JSONException e) {
            log.error("JSONException: {}", e.getMessage());
            return text;
        }
    }

    private HttpServletRequest wrapRequest(HttpServletRequest request) throws IOException {
        if (!isVisible(getMediaType(request.getContentType()))) {
            return request;
        }
        if (request instanceof CustomRequestWrapper) {
            return request;
        } else {
            return new CustomRequestWrapper(request);
        }
    }

    private ContentCachingResponseWrapper wrapResponse(HttpServletResponse response) {
//        if (!isVisible(getMediaType(response.getContentType()))) {
//            return response;
//        }
        if (response instanceof ContentCachingResponseWrapper) {
            return (ContentCachingResponseWrapper) response;
        } else {
            return new ContentCachingResponseWrapper(response);
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    private static class HttpLog {
        private String method;
        private String contentType;
        private String uri;
        private Map<String, Collection<String>> headers;
        private String rawBody;
        private Integer status;
    }
}
