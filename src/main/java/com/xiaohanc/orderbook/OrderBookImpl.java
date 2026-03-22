package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;

public class OrderBookImpl implements OrderBook {
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> orderById = new HashMap<>();
    private final OrderMatchListener listener;

    public OrderBookImpl(OrderMatchListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    @Override
    public void addOrder(long id, Order.Side side, long price, long quantity) {
        long remainingQuantity = matchOrder(id, side, price, quantity);
        if (remainingQuantity == 0) {
            return;
        }

        NavigableMap<Long, PriceLevel> book = side == Order.Side.BUY ? bids : asks;
        PriceLevel level = book.computeIfAbsent(price, PriceLevel::new);
        RestingOrder order = new RestingOrder(id, side, price, remainingQuantity, level);
        level.append(order);
        orderById.put(id, order);
    }

    @Override
    public void cancelOrder(long id) {
        RestingOrder order = orderById.remove(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        removeOrder(order);
    }

    @Override
    public void modifyOrder(long id, long newPrice, long newQuantity) {
        RestingOrder order = orderById.get(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        Order.Side side = order.side;
        cancelOrder(id);
        addOrder(id, side, newPrice, newQuantity);
    }

    @Override
    public List<Order> getBids() {
        return snapshot(bids);
    }

    @Override
    public List<Order> getAsks() {
        return snapshot(asks);
    }

    private long matchOrder(long incomingId, Order.Side incomingSide, long incomingPrice, long incomingQuantity) {
        NavigableMap<Long, PriceLevel> oppositeBook = incomingSide == Order.Side.BUY ? asks : bids;
        long remainingQuantity = incomingQuantity;

        while (remainingQuantity > 0) {
            Map.Entry<Long, PriceLevel> bestEntry = oppositeBook.firstEntry();
            if (bestEntry == null || !crosses(incomingSide, incomingPrice, bestEntry.getKey())) {
                break;
            }

            PriceLevel level = bestEntry.getValue();
            RestingOrder maker = level.head;
            while (maker != null && remainingQuantity > 0) {
                RestingOrder nextMaker = maker.next;
                long matchedQuantity = Math.min(remainingQuantity, maker.quantity);
                listener.onMatch(maker.id, incomingId, maker.price, matchedQuantity);

                remainingQuantity -= matchedQuantity;
                maker.quantity -= matchedQuantity;
                if (maker.quantity == 0) {
                    orderById.remove(maker.id);
                    removeOrder(maker);
                }
                maker = nextMaker;
            }
        }

        return remainingQuantity;
    }

    private boolean crosses(Order.Side incomingSide, long incomingPrice, long restingPrice) {
        return incomingSide == Order.Side.BUY
                ? incomingPrice >= restingPrice
                : incomingPrice <= restingPrice;
    }

    private void removeOrder(RestingOrder order) {
        PriceLevel level = order.level;
        level.unlink(order);
        if (level.isEmpty()) {
            NavigableMap<Long, PriceLevel> book = order.side == Order.Side.BUY ? bids : asks;
            book.remove(level.price);
        }
    }

    private List<Order> snapshot(NavigableMap<Long, PriceLevel> book) {
        List<Order> orders = new ArrayList<>(orderById.size());
        for (PriceLevel level : book.values()) {
            for (RestingOrder order = level.head; order != null; order = order.next) {
                orders.add(new Order(order.id, order.side, order.price, order.quantity));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static final class PriceLevel {
        private final long price;
        private RestingOrder head;
        private RestingOrder tail;

        private PriceLevel(long price) {
            this.price = price;
        }

        private void append(RestingOrder order) {
            if (tail == null) {
                head = order;
                tail = order;
                return;
            }

            tail.next = order;
            order.prev = tail;
            tail = order;
        }

        private void unlink(RestingOrder order) {
            RestingOrder prev = order.prev;
            RestingOrder next = order.next;
            if (prev == null) {
                head = next;
            } else {
                prev.next = next;
            }
            if (next == null) {
                tail = prev;
            } else {
                next.prev = prev;
            }
            order.prev = null;
            order.next = null;
        }

        private boolean isEmpty() {
            return head == null;
        }
    }

    private static final class RestingOrder {
        private final long id;
        private final Order.Side side;
        private final long price;
        private long quantity;
        private final PriceLevel level;
        private RestingOrder prev;
        private RestingOrder next;

        private RestingOrder(long id, Order.Side side, long price, long quantity, PriceLevel level) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.level = level;
        }
    }
}
