package org.uvo.uvostore.service.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.customer.ShippingAddress;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.AdminOrderSummaryDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminCustomerServiceImpl implements AdminCustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    public AdminCustomerServiceImpl(CustomerRepository customerRepository, OrderRepository orderRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCustomerSummaryDto> search(String search, Pageable pageable) {
        Specification<Customer> spec = null;
        if (search != null && !search.isBlank()) {
            String term = "%" + search.toLowerCase() + "%";
            spec = (root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("firstName")), term),
                    cb.like(cb.lower(root.get("lastName")), term),
                    cb.like(cb.lower(root.get("email")), term),
                    cb.like(cb.lower(root.get("phone")), term)
            );
        }
        return customerRepository.findAll(spec, pageable).map(this::toSummaryDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminCustomerStatsDto getStats() {
        Instant startOfMonth = Instant.now().atZone(ZoneOffset.UTC).with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return new AdminCustomerStatsDto(
                customerRepository.count(),
                customerRepository.countWithOrders(),
                customerRepository.countByCreatedAtAfter(startOfMonth)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AdminCustomerDetailDto getById(Long id) {
        Customer customer = findOrThrow(id);
        List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(id);

        long totalOrders = orders.size();
        BigDecimal totalSpent = orders.stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageOrder = totalOrders > 0
                ? totalSpent.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        long completedOrders = orders.stream().filter(o -> o.getStatus() == OrderStatus.DELIVERED).count();

        List<ShippingAddressDto> addresses = customer.getShippingAddresses().stream()
                .map(this::toAddressDto)
                .toList();

        return new AdminCustomerDetailDto(
                customer.getId(), customer.getEmail(), customer.getFirstName(), customer.getLastName(),
                customer.getPhone(), customer.getAccountStatus().name(), addresses,
                new AdminCustomerOrderStatsDto(totalOrders, totalSpent, averageOrder, completedOrders),
                customer.getCreatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminOrderSummaryDto> getOrders(Long customerId, Pageable pageable) {
        Specification<Order> spec = (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        return orderRepository.findAll(spec, pageable).map(this::toOrderSummaryDto);
    }

    @Override
    @Transactional
    public void deleteCustomer(Long id) {
        Customer customer = findOrThrow(id);
        if (orderRepository.findByCustomerIdOrderByCreatedAtDesc(id).size() > 0) {
            throw new IllegalStateException("No se puede eliminar un cliente con órdenes asociadas");
        }
        customerRepository.delete(customer);
    }

    private Customer findOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer " + id + " not found"));
    }

    private AdminCustomerSummaryDto toSummaryDto(Customer c) {
        return new AdminCustomerSummaryDto(
                c.getId(), c.getEmail(), c.getFirstName(), c.getLastName(), c.getPhone(),
                c.getAccountStatus().name(), c.getOrders().size(), c.getCreatedAt()
        );
    }

    private AdminOrderSummaryDto toOrderSummaryDto(Order order) {
        return new AdminOrderSummaryDto(
                order.getId(), order.getOrderNumber(), order.getCustomerEmail(), order.getCustomerFirstName(),
                order.getCustomerLastName(), order.getStatus().name(), order.getPaymentStatus().name(),
                order.getFulfillmentStatus().name(), order.getTotal(), order.getItems().size(), order.getCreatedAt()
        );
    }

    private ShippingAddressDto toAddressDto(ShippingAddress address) {
        return new ShippingAddressDto(
                address.getId(), address.getFirstName(), address.getLastName(), address.getCompany(),
                address.getAddressLine1(), address.getAddressLine2(), address.getCity(), address.getState(),
                address.getPostalCode(), address.getCountry(), address.getPhone(), address.isDefault()
        );
    }
}
