package com.xiaohanc.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {
    private OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBookImpl((mId, tId, p, q) -> {});
    }

    @Test
    void testAddBuyOrder() {
        Order buyOrder = new Order(1L, Order.Side.BUY, 10000L, 10L);
        orderBook.addOrder(buyOrder.id(), buyOrder.side(), buyOrder.price(), buyOrder.quantity());
        
        List<Order> bids = orderBook.getBids();
        assertEquals(1, bids.size());
        assertEquals(buyOrder, bids.get(0));
    }

    @Test
    void testMatchingSamePrice() {
        Order sellOrder = new Order(1L, Order.Side.SELL, 100L, 10L);
        Order buyOrder = new Order(2L, Order.Side.BUY, 100L, 10L);
        
        orderBook.addOrder(sellOrder.id(), sellOrder.side(), sellOrder.price(), sellOrder.quantity());
        orderBook.addOrder(buyOrder.id(), buyOrder.side(), buyOrder.price(), buyOrder.quantity());
        
        assertTrue(orderBook.getAsks().isEmpty(), "Asks should be empty after full match");
        assertTrue(orderBook.getBids().isEmpty(), "Bids should be empty after full match");
    }

    @Test
    void testPartialMatching() {
        Order sellOrder = new Order(1L, Order.Side.SELL, 100L, 10L);
        Order buyOrder = new Order(2L, Order.Side.BUY, 100L, 7L);
        
        orderBook.addOrder(sellOrder.id(), sellOrder.side(), sellOrder.price(), sellOrder.quantity());
        orderBook.addOrder(buyOrder.id(), buyOrder.side(), buyOrder.price(), buyOrder.quantity());
        
        assertEquals(1, orderBook.getAsks().size());
        assertEquals(3L, orderBook.getAsks().get(0).quantity());
        assertTrue(orderBook.getBids().isEmpty());
    }

    @Test
    void testMatchingWithBetterPrice() {
        Order sellOrder = new Order(1L, Order.Side.SELL, 90L, 10L);
        Order buyOrder = new Order(2L, Order.Side.BUY, 100L, 10L);
        
        orderBook.addOrder(sellOrder.id(), sellOrder.side(), sellOrder.price(), sellOrder.quantity());
        orderBook.addOrder(buyOrder.id(), buyOrder.side(), buyOrder.price(), buyOrder.quantity());
        
        assertTrue(orderBook.getAsks().isEmpty());
        assertTrue(orderBook.getBids().isEmpty());
    }

    @Test
    void testMatchEventFired() {
        List<long[]> events = new ArrayList<>();
        orderBook = new OrderBookImpl((mId, tId, p, q) -> events.add(new long[]{mId, tId, p, q}));

        orderBook.addOrder(1L, Order.Side.SELL, 100L, 10L);
        orderBook.addOrder(2L, Order.Side.BUY, 100L, 5L);

        assertEquals(1, events.size());
        long[] event = events.getFirst();
        assertEquals(1L, event[0]);
        assertEquals(2L, event[1]);
        assertEquals(100L, event[2]);
        assertEquals(5L, event[3]);
    }

    @Test
    void testFIFOPriority() {
        // Two sell orders at the same price
        orderBook.addOrder(1L, Order.Side.SELL, 100L, 10L);
        orderBook.addOrder(2L, Order.Side.SELL, 100L, 10L);

        // One buy order that matches only the first sell order partially
        orderBook.addOrder(3L, Order.Side.BUY, 100L, 15L);

        // The first sell order should be fully matched and removed
        // The second sell order should remain with its full quantity
        List<Order> asks = orderBook.getAsks();
        assertEquals(1, asks.size());
        assertEquals(2L, asks.get(0).id());
        assertEquals(5L, asks.get(0).quantity());
    }

    @Test
    void testMatchingMultipleLevels() {
        // Three sell orders at different prices
        orderBook.addOrder(1L, Order.Side.SELL, 100L, 10L);
        orderBook.addOrder(2L, Order.Side.SELL, 110L, 10L);
        orderBook.addOrder(3L, Order.Side.SELL, 120L, 10L);

        // One buy order that matches the first two levels and part of the third
        orderBook.addOrder(4L, Order.Side.BUY, 120L, 25L);

        assertTrue(orderBook.getBids().isEmpty());
        List<Order> asks = orderBook.getAsks();
        assertEquals(1, asks.size());
        assertEquals(3L, asks.get(0).id());
        assertEquals(5L, asks.get(0).quantity());
    }

    @Test
    void testFarOutPriceLevelsRemainOrdered() {
        orderBook.addOrder(1L, Order.Side.BUY, 100L, 10L);
        orderBook.addOrder(2L, Order.Side.BUY, 1L, 5L);
        orderBook.addOrder(3L, Order.Side.BUY, 99L, 7L);

        List<Order> bids = orderBook.getBids();
        assertEquals(List.of(1L, 3L, 2L), bids.stream().map(Order::id).toList());

        orderBook.addOrder(4L, Order.Side.SELL, 101L, 4L);
        orderBook.addOrder(5L, Order.Side.SELL, 1_000_000_000L, 2L);
        orderBook.addOrder(6L, Order.Side.SELL, 102L, 3L);

        List<Order> asks = orderBook.getAsks();
        assertEquals(List.of(4L, 6L, 5L), asks.stream().map(Order::id).toList());
    }

    @Test
    void testNegativeAndPositivePricesRemainOrderedAndMatch() {
        orderBook.addOrder(1L, Order.Side.BUY, -5L, 2L);
        orderBook.addOrder(2L, Order.Side.BUY, 3L, 4L);
        orderBook.addOrder(3L, Order.Side.BUY, -1L, 1L);

        List<Order> bids = orderBook.getBids();
        assertEquals(List.of(2L, 3L, 1L), bids.stream().map(Order::id).toList());

        orderBook.addOrder(4L, Order.Side.SELL, -2L, 3L);

        bids = orderBook.getBids();
        assertEquals(3, bids.size());
        assertEquals(2L, bids.get(0).id());
        assertEquals(1L, bids.get(0).quantity());
        assertEquals(3L, bids.get(1).id());
        assertEquals(1L, bids.get(1).quantity());
        assertEquals(1L, bids.get(2).id());
        assertEquals(2L, bids.get(2).quantity());
        assertTrue(orderBook.getAsks().isEmpty());

        orderBook.addOrder(5L, Order.Side.SELL, -10L, 1L);
        bids = orderBook.getBids();
        assertEquals(2, bids.size());
        assertEquals(List.of(3L, 1L), bids.stream().map(Order::id).toList());
        assertEquals(1L, bids.get(0).quantity());
        assertEquals(2L, bids.get(1).quantity());
        assertTrue(orderBook.getAsks().isEmpty());
    }

    @Test
    void testCancelOrder() {
        orderBook.addOrder(1L, Order.Side.BUY, 100L, 10L);
        orderBook.cancelOrder(1L);
        
        assertTrue(orderBook.getBids().isEmpty());
    }

    @Test
    void testModifyOrder() {
        orderBook.addOrder(1L, Order.Side.BUY, 100L, 10L);
        orderBook.modifyOrder(1L, 105L, 15L);
        
        assertTrue(orderById(orderBook.getBids(), 1L).isPresent());
        Order newOrder = orderById(orderBook.getBids(), 1L).get();
        assertEquals(105L, newOrder.price());
        assertEquals(15L, newOrder.quantity());
    }

    private Optional<Order> orderById(List<Order> orders, long id) {
        return orders.stream().filter(o -> o.id() == id).findFirst();
    }

    @Test
    void testCancelOrderExcessiveQuantity() {
        orderBook.addOrder(1L, Order.Side.SELL, 100L, 10L);
        orderBook.cancelOrder(1L); 
        
        assertTrue(orderBook.getAsks().isEmpty());
    }

    @Test
    void testCancelNonExistentOrderThrows() {
        assertThrows(NoSuchElementException.class, () -> orderBook.cancelOrder(999L));
    }

    @Test
    void testModifyNonExistentOrderThrows() {
        assertThrows(NoSuchElementException.class, () -> orderBook.modifyOrder(999L, 100L, 10L));
    }

}
