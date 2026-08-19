package org.uvo.uvostore.service.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.common.Address;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminOrderQueryServiceImpl implements AdminOrderQueryService {

    private final OrderRepository orderRepository;

    public AdminOrderQueryServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminOrderSummaryDto> search(AdminOrderSearchCriteria criteria, Pageable pageable) {
        return orderRepository.findAll(buildSpecification(criteria), pageable).map(this::toSummaryDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOrderStatsDto getStats() {
        return new AdminOrderStatsDto(
                orderRepository.count(),
                orderRepository.count((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.PENDING)),
                orderRepository.count((root, query, cb) -> cb.equal(root.get("paymentStatus"), PaymentStatus.PAID)),
                orderRepository.count((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.PROCESSING)),
                orderRepository.count((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.SHIPPED)),
                orderRepository.count((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.CANCELLED))
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOrderDetailDto getById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order " + id + " not found"));
        return toDetailDto(order);
    }

    private Specification<Order> buildSpecification(AdminOrderSearchCriteria criteria) {
        List<Specification<Order>> specs = new ArrayList<>();

        if (criteria.search() != null && !criteria.search().isBlank()) {
            String term = "%" + criteria.search().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("orderNumber")), term),
                    cb.like(cb.lower(root.get("customerEmail")), term),
                    cb.like(cb.lower(root.get("customerFirstName")), term),
                    cb.like(cb.lower(root.get("customerLastName")), term)
            ));
        }

        if (criteria.tab() != null) {
            switch (criteria.tab()) {
                case "pending" -> specs.add((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.PENDING));
                case "paid" -> specs.add((root, query, cb) -> cb.equal(root.get("paymentStatus"), PaymentStatus.PAID));
                case "processing" -> specs.add((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.PROCESSING));
                case "shipped" -> specs.add((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.SHIPPED));
                case "cancelled" -> specs.add((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.CANCELLED));
                default -> { /* "all" — no filter */ }
            }
        }

        if (criteria.paymentStatus() != null && !criteria.paymentStatus().isBlank()) {
            PaymentStatus status = PaymentStatus.valueOf(criteria.paymentStatus().toUpperCase());
            specs.add((root, query, cb) -> cb.equal(root.get("paymentStatus"), status));
        }

        return specs.stream().reduce(Specification::and).orElse(null);
    }

    private AdminOrderSummaryDto toSummaryDto(Order order) {
        return new AdminOrderSummaryDto(
                order.getId(), order.getOrderNumber(), order.getCustomerEmail(), order.getCustomerFirstName(),
                order.getCustomerLastName(), order.getStatus().name(), order.getPaymentStatus().name(),
                order.getFulfillmentStatus().name(), order.getTotal(), order.getItems().size(), order.getCreatedAt()
        );
    }

    private AdminOrderDetailDto toDetailDto(Order order) {
        List<AdminOrderItemDto> items = order.getItems().stream()
                .map(this::toItemDto)
                .toList();

        return new AdminOrderDetailDto(
                order.getId(), order.getOrderNumber(),
                order.getCustomer() != null ? order.getCustomer().getId() : null,
                order.getCustomerEmail(), order.getCustomerFirstName(), order.getCustomerLastName(), order.getCustomerPhone(),
                order.getStatus().name(), order.getPaymentStatus().name(), order.getFulfillmentStatus().name(),
                order.getSubtotal(), order.getDiscountAmount(), order.getShippingCost(), order.getTaxAmount(), order.getTotal(),
                order.getPaymentMethod(), order.getTrackingNumber(), order.getTrackingUrl(),
                toAddressDto(order.getShippingAddress()), toAddressDto(order.getBillingAddress()),
                order.getShippingRegion(), order.getShippingCommune(), order.getShippingPostalCode(),
                order.getCustomerNotes(), order.getNotes(), items,
                order.getCreatedAt(), order.getShippedAt(), order.getDeliveredAt()
        );
    }

    private AdminOrderItemDto toItemDto(OrderItem item) {
        return new AdminOrderItemDto(
                item.getId(), item.getProduct().getId(), item.getProductName(), item.getProductSku(),
                item.getVariation() != null ? item.getVariation().getId() : null,
                item.getQuantity(), item.getPrice(), item.getSubtotal()
        );
    }

    private AddressDto toAddressDto(Address address) {
        if (address == null) {
            return null;
        }
        return new AddressDto(
                address.getFirstName(), address.getLastName(), address.getCompany(), address.getAddressLine1(),
                address.getAddressLine2(), address.getCity(), address.getState(), address.getPostalCode(),
                address.getCountry(), address.getPhone()
        );
    }
}
