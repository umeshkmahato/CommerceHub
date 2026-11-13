package com.commercehub.order;

import com.commercehub.order.entity.Order;
import com.commercehub.order.entity.OrderItem;
import com.commercehub.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Order repository IT — real PostgreSQL 16: jsonb shipping address,
 *  order-number lookup, paginated customer history, state transitions. */
@Testcontainers
@SpringBootTest
class OrderRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orderdb");

    @Autowired OrderRepository orderRepo;

    private Order newOrder(Long customerId, String orderNumber) {
        Order o = new Order();
        o.setOrderNumber(orderNumber);
        o.setCustomerId(customerId);
        o.setShippingAddress("{\"city\":\"NYC\"}");
        o.setSubtotal(new BigDecimal("100.00"));
        o.setTaxAmount(new BigDecimal("8.00"));
        o.setShippingAmount(BigDecimal.ZERO);
        o.setTotalAmount(new BigDecimal("108.00"));
        OrderItem i = new OrderItem();
        i.setOrder(o); i.setProductId(1L); i.setSku("S1"); i.setProductName("W");
        i.setQuantity(1); i.setUnitPrice(new BigDecimal("100.00"));
        i.setLineTotal(new BigDecimal("100.00"));
        o.getItems().add(i);
        return o;
    }

    @Test
    void orderPersistsWithJsonbAddressAndItems() {
        Order saved = orderRepo.saveAndFlush(newOrder(42L, "ORD-IT-001"));

        Order found = orderRepo.findByOrderNumber("ORD-IT-001").orElseThrow();
        assertEquals(saved.getId(), found.getId());
        assertEquals(Order.Status.PENDING, found.getStatus());
        assertEquals("{\"city\":\"NYC\"}", found.getShippingAddress());
        assertEquals(1, found.getItems().size());
    }

    @Test
    void history_returnsOnlyCustomersOrdersNewestFirst() {
        orderRepo.save(newOrder(7L, "ORD-HIST-A"));
        orderRepo.save(newOrder(7L, "ORD-HIST-B"));
        orderRepo.save(newOrder(8L, "ORD-OTHER"));

        var page = orderRepo.findByCustomerIdOrderByCreatedAtDesc(7L, PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream()
                .allMatch(o -> o.getCustomerId().equals(7L)));
    }

    @Test
    void duplicateOrderNumber_violatesUniqueConstraint() {
        orderRepo.saveAndFlush(newOrder(9L, "ORD-DUP"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> orderRepo.saveAndFlush(newOrder(10L, "ORD-DUP")));
    }

    @Test
    void stateMachine_illegalTransitionThrows() {
        Order o = orderRepo.saveAndFlush(newOrder(11L, "ORD-SM"));
        assertThrows(IllegalStateException.class, () -> o.deliver());
        o.confirm();
        assertThrows(IllegalStateException.class, () -> o.confirm());
    }
}
