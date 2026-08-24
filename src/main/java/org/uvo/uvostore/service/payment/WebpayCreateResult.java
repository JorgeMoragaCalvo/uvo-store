package org.uvo.uvostore.service.payment;

// The frontend must render an auto-submitting HTML form (method=POST, action=url, hidden field
// token_ws=token) — Webpay Plus's redirect flow is not a simple GET, unlike Stripe's session.url.
public record WebpayCreateResult(String token, String url) {
}
