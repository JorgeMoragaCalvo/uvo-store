package org.uvo.uvostore.service.payment;

import cl.transbank.util.HttpUtil;
import cl.transbank.util.HttpUtilImpl;
import cl.transbank.webpay.exception.WebpayException;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * GAP-08. El cliente HTTP del SDK de Transbank con otro origen: cambia {@code https://webpay3gint.transbank.cl}
 * (o el de producción) por {@code apiBase} y conserva la ruta, {@code /rswebpaytransaction/...}. El SDK no deja
 * fijar una URL propia —cada {@code IntegrationType} trae la suya—, pero sí sustituir su {@code HttpUtil}.
 *
 * <p>Solo lo instala {@code GatewayApiBaseConfig}, con {@code WEBPAY_API_BASE} puesta, para el E2E.
 */
public class RewritingHttpUtil implements HttpUtil {

    private final String apiBase;
    private final HttpUtil delegate;

    public RewritingHttpUtil(String apiBase) {
        this(apiBase, HttpUtilImpl.getInstance());
    }

    RewritingHttpUtil(String apiBase, HttpUtil delegate) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.delegate = delegate;
    }

    /** {@code getFile()} es la ruta con la query: todo lo que va después del origen. */
    URL rewrite(URL url) throws IOException {
        return URI.create(apiBase + url.getFile()).toURL();
    }

    @Override
    public <T> T request(URL url, RequestMethod method, Object request, Map<String, String> headers, Class<T> clazz)
            throws IOException, WebpayException {
        return delegate.request(rewrite(url), method, request, headers, clazz);
    }

    @Override
    public <T> List<T> requestList(URL url, RequestMethod method, Object request, Map<String, String> headers,
                                   Class<T[]> clazz) throws IOException, WebpayException {
        return delegate.requestList(rewrite(url), method, request, headers, clazz);
    }

    @Override
    public String request(URL url, RequestMethod method, String query) throws IOException, WebpayException {
        return delegate.request(rewrite(url), method, query);
    }

    @Override
    public String request(URL url, RequestMethod method, String query, ContentType contentType)
            throws IOException, WebpayException {
        return delegate.request(rewrite(url), method, query, contentType);
    }

    @Override
    public String request(URL url, RequestMethod method, String query, Map<String, String> headers)
            throws IOException, WebpayException {
        return delegate.request(rewrite(url), method, query, headers);
    }

    @Override
    public String request(URL url, RequestMethod method, String query, ContentType contentType,
                          Map<String, String> headers) throws IOException, WebpayException {
        return delegate.request(rewrite(url), method, query, contentType, headers);
    }

    @Override
    public void setConnectTimeout(int connectTimeout) {
        delegate.setConnectTimeout(connectTimeout);
    }

    @Override
    public void setReadTimeout(int readTimeout) {
        delegate.setReadTimeout(readTimeout);
    }
}
