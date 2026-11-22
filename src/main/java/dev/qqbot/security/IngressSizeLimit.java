package dev.qqbot.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IngressSizeLimit extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/callbacks/")
                && !request.getRequestURI().startsWith("/api/ingress/")) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(131073);
        if (body.length > 131072) {
            response.sendError(413);
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        var bytes = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() {
                                return bytes.read();
                            }

                            public boolean isFinished() {
                                return bytes.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(
                                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
