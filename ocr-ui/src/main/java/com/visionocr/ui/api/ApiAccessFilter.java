package com.visionocr.ui.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /api/v1/** (external systems): needs a valid X-API-Key header; keys come from api.keys.&lt;client&gt;=&lt;key&gt;.
 * Everything else (the demo UI and its internal /api): only from this computer, even when the server listens on
 * the network - the UI has no login.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessFilter extends OncePerRequestFilter {

    public static final String CLIENT = "apiClient";
    static final String HEADER = "X-API-Key";
    private static final int MIN_KEY_LENGTH = 24;
    private static final Logger log = LoggerFactory.getLogger(ApiAccessFilter.class);

    private final Map<String, byte[]> keys = new LinkedHashMap<>();   // client -> key

    @Autowired        // two constructors: tell Spring which one to use (the other is for tests)
    public ApiAccessFilter(Environment env) {
        this(Binder.get(env).bind("api.keys", Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
    }

    ApiAccessFilter(Map<String, String> configured) {
        configured.forEach((client, key) -> {
            if (key == null || key.length() < MIN_KEY_LENGTH || !client.matches("[A-Za-z0-9-]{1,30}")) {
                log.warn("API key for '{}' ignored: client name must be letters/digits/-, key at least {} characters",
                        client, MIN_KEY_LENGTH);
            } else {
                keys.put(client, key.getBytes(StandardCharsets.UTF_8));
            }
        });
        log.info("External API: {} client key(s) configured {}", keys.size(), keys.keySet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith(req.getContextPath() + "/api/v1/")) {
            String client = clientFor(req.getHeader(HEADER));
            if (client == null) {
                deny(res, HttpServletResponse.SC_UNAUTHORIZED, keys.isEmpty()
                        ? "The external API is off: no API keys configured (api.keys.<client>=<key>)"
                        : "Missing or invalid " + HEADER + " header");
                return;
            }
            req.setAttribute(CLIENT, client);
        } else if (!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()) {
            // ponytail: behind a reverse proxy every caller looks local; put the proxy in front of /api/v1 only
            deny(res, HttpServletResponse.SC_FORBIDDEN, "The demo UI is only available on the computer it runs on");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Client name for a key, or null. Compares against every key in constant time. */
    String clientFor(String key) {
        if (key == null) {
            return null;
        }
        byte[] given = key.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            if (MessageDigest.isEqual(given, e.getValue())) {
                match = e.getKey();
            }
        }
        return match;
    }

    private static void deny(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
