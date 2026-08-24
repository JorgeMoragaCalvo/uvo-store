package org.uvo.uvostore.service.payment;

public interface WebpayService {
    WebpayCreateResult createTransaction(Long orderId, String returnUrl);
    WebpayCommitResult commitTransaction(String token);
}
