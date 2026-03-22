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
    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
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

        SideBook book = side == Order.Side.BUY ? bids : asks;
        PriceLevel level = book.level(price);
        if (level == null) {
            level = book.addLevel(price);
        }
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
        return snapshot(bids.levels);
    }

    @Override
    public List<Order> getAsks() {
        return snapshot(asks.levels);
    }

    private long matchOrder(long incomingId, Order.Side incomingSide, long incomingPrice, long incomingQuantity) {
        SideBook oppositeBook = incomingSide == Order.Side.BUY ? asks : bids;
        long remainingQuantity = incomingQuantity;

        while (remainingQuantity > 0) {
            PriceLevel level = oppositeBook.best;
            if (level == null || !crosses(incomingSide, incomingPrice, level.price)) {
                break;
            }

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
            level.book.removeLevel(level);
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
        private final SideBook book;
        private final long price;
        private RestingOrder head;
        private RestingOrder tail;
        private PriceLevel better;
        private PriceLevel worse;

        private PriceLevel(SideBook book, long price) {
            this.book = book;
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

    private static final class SideBook {
        private final NavigableMap<Long, PriceLevel> levels;
        private final boolean buySide;
        private PriceLevel best;

        private SideBook(boolean buySide) {
            this.buySide = buySide;
            this.levels = buySide ? new TreeMap<>(Collections.reverseOrder()) : new TreeMap<>();
        }

        private PriceLevel level(long price) {
            return levels.get(price);
        }

        private PriceLevel addLevel(long price) {
            PriceLevel level = new PriceLevel(this, price);
            Map.Entry<Long, PriceLevel> betterEntry = buySide ? levels.lowerEntry(price) : levels.lowerEntry(price);
            Map.Entry<Long, PriceLevel> worseEntry = buySide ? levels.higherEntry(price) : levels.higherEntry(price);
            PriceLevel better = betterEntry == null ? null : betterEntry.getValue();
            PriceLevel worse = worseEntry == null ? null : worseEntry.getValue();

            level.better = better;
            level.worse = worse;
            if (better == null) {
                best = level;
            } else {
                better.worse = level;
            }
            if (worse != null) {
                worse.better = level;
            }

            levels.put(price, level);
            return level;
        }

        private void removeLevel(PriceLevel level) {
            PriceLevel better = level.better;
            PriceLevel worse = level.worse;
            if (better == null) {
                best = worse;
            } else {
                better.worse = worse;
            }
            if (worse != null) {
                worse.better = better;
            }

            level.better = null;
            level.worse = null;
            levels.remove(level.price);
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
