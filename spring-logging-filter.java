

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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
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
            doFilterWrapped(wrapRequest(request), wrapResponse(response), filterChain);
        }
        MDC.clear();
    }

    protected void doFilterWrapped(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, FilterChain filterChain) throws ServletException, IOException {
        try {
            printRequestLog(createRequestLog(request));
            filterChain.doFilter(request, response);
        } finally {
            printResponseLog(createResponseLog(response));
            response.copyBodyToResponse();
        }
    }

    private static void printResponseLog(HttpLog responseLogData) {
        log.info("Response : status={}, headers={}, content-type={}, body={}", responseLogData.getStatus(), responseLogData.getHeaders(), responseLogData.getContentType(), responseLogData.getRawBody());
    }

    private static void printRequestLog(HttpLog requestLogData) {
        log.info("Request : method={}, uri={}, headers={}, content-type={}, body={}", requestLogData.getMethod(), requestLogData.getUri(), requestLogData.getHeaders(), requestLogData.getContentType(), requestLogData.getRawBody());
    }

    private HttpLog createRequestLog(ContentCachingRequestWrapper request) {
        HttpLog requestLog = HttpLog.builder()
                .method(request.getMethod())
                .uri(getUri(request))
                .contentType(request.getContentType())
                .build();
        setRequestHeaders(request, requestLog);
        setRequestContentString(request, requestLog);
        return requestLog;
    }

    private static String getUri(ContentCachingRequestWrapper request) {
        String queryString = request.getQueryString();
        if (StringUtils.hasText(queryString)) {
            return request.getRequestURL().append("?").append(queryString).toString();
        } else {
            return request.getRequestURL().toString();
        }
    }

    private void setRequestContentString(ContentCachingRequestWrapper request, HttpLog requestLog) {
        try {
            byte[] contentAsByteArray = request.getContentAsByteArray();
            if (contentAsByteArray.length == 0) {
                request.getParameterMap(); //force open file stream
                contentAsByteArray = request.getInputStream().readAllBytes(); //content caching
            }
            requestLog.rawBody = request.getContentType() == null ? null : getContentString(contentAsByteArray, request.getContentType(), request.getCharacterEncoding());
        } catch (IOException e) {
            log.error("IOException = {}", e.getMessage());
        }
    }

    private static void setRequestHeaders(ContentCachingRequestWrapper request, HttpLog requestLog) {
        Collections.list(request.getHeaderNames()).forEach(headerName -> requestLog.headers.put(headerName, Collections.list(request.getHeaders(headerName))));
    }

    private HttpLog createResponseLog(ContentCachingResponseWrapper response) {
        HttpLog responseLog = HttpLog.builder()
                .status(response.getStatus())
                .contentType(response.getContentType())
                .build();
        setResponseHeaders(response, responseLog);
        setResponseContentString(response, responseLog);
        return responseLog;
    }

    private void setResponseContentString(ContentCachingResponseWrapper response, HttpLog responseLog) {
        try {
            byte[] contentAsByteArray = response.getContentInputStream().readAllBytes();
            responseLog.rawBody = responseLog.getContentType() == null ? null : getContentString(contentAsByteArray, response.getContentType(), response.getCharacterEncoding());
        } catch (IOException e) {
            log.error("IOException = {}", e.getMessage());
        }
    }

    private static void setResponseHeaders(ContentCachingResponseWrapper response, HttpLog responseLog) {
        response.getHeaderNames().forEach(headerName -> responseLog.headers.put(headerName, response.getHeaders(headerName)));
    }

    private String getContentString(byte[] contentAsByteArray, @NotNull String contentType, String contentEncoding) {
        MediaType mediaType = MediaType.valueOf(contentType);
        boolean visible = VISIBLE_TYPES.stream().anyMatch(visibleType -> visibleType.includes(mediaType));
        String text = null;
        try {
            text = new String(contentAsByteArray, contentEncoding);
            if (visible) {
                if (mediaType.equals(MediaType.APPLICATION_JSON)) {
                    return new JSONObject(text).toString();
                }
                return text;
            } else {
                return "Binary Content";
            }
        } catch (UnsupportedEncodingException e) {
            log.info("UnsupportedEncodingException: {}", e.getMessage());
            return null;
        } catch (JSONException e) {
            log.error("JSONException: {}", e.getMessage());
            return text;
        }
    }

    private ContentCachingRequestWrapper wrapRequest(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper) {
            return (ContentCachingRequestWrapper) request;
        } else {
            return new ContentCachingRequestWrapper(request);
        }
    }

    private ContentCachingResponseWrapper wrapResponse(HttpServletResponse response) {
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
        private final Map<String, Collection<String>> headers = new HashMap<>();
        private String rawBody;
        private Integer status;
    }
}
