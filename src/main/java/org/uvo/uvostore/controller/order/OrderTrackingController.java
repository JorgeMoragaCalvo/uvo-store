package org.uvo.uvostore.controller.order;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.OrderTrackingDto;
import org.uvo.uvostore.service.order.OrderTrackingService;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Seguimiento de pedidos (público)", description = "Consulta del estado de una orden por número, sin autenticación")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderTrackingController {

    private final OrderTrackingService orderTrackingService;

    public OrderTrackingController(OrderTrackingService orderTrackingService) {
        this.orderTrackingService = orderTrackingService;
    }

    @GetMapping("/track")
    public OrderTrackingDto track(@RequestParam("order_number") String orderNumber) {
        return orderTrackingService.track(orderNumber);
    }
}
