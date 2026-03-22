package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

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
        return snapshot(bids);
    }

    @Override
    public List<Order> getAsks() {
        return snapshot(asks);
    }

    private long matchOrder(long incomingId, Order.Side incomingSide, long incomingPrice, long incomingQuantity) {
        SideBook oppositeBook = incomingSide == Order.Side.BUY ? asks : bids;
        long remainingQuantity = incomingQuantity;

        while (remainingQuantity > 0) {
            PriceLevel level = oppositeBook.best();
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

    private List<Order> snapshot(SideBook book) {
        List<PriceLevel> levels = book.snapshotLevels();
        List<Order> orders = new ArrayList<>(orderById.size());
        for (PriceLevel level : levels) {
            for (RestingOrder order = level.head; order != null; order = order.next) {
                orders.add(new Order(order.id, order.side, order.price, order.quantity));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static final class SideBook {
        private static final int INITIAL_HEAP_CAPACITY = 16;

        private final boolean buySide;
        private final Map<Long, PriceLevel> levels = new HashMap<>();
        private PriceLevel[] heap = new PriceLevel[INITIAL_HEAP_CAPACITY];
        private int heapSize;

        private SideBook(boolean buySide) {
            this.buySide = buySide;
        }

        private PriceLevel level(long price) {
            return levels.get(price);
        }

        private PriceLevel addLevel(long price) {
            PriceLevel level = new PriceLevel(this, price);
            levels.put(price, level);
            push(level);
            return level;
        }

        private PriceLevel best() {
            return heapSize == 0 ? null : heap[0];
        }

        private void removeLevel(PriceLevel level) {
            levels.remove(level.price);
            removeAt(level.heapIndex);
        }

        private List<PriceLevel> snapshotLevels() {
            List<PriceLevel> orderedLevels = new ArrayList<>(levels.values());
            orderedLevels.sort((left, right) -> buySide
                    ? Long.compare(right.price, left.price)
                    : Long.compare(left.price, right.price));
            return orderedLevels;
        }

        private void push(PriceLevel level) {
            if (heapSize == heap.length) {
                PriceLevel[] expanded = new PriceLevel[heap.length << 1];
                System.arraycopy(heap, 0, expanded, 0, heap.length);
                heap = expanded;
            }

            heap[heapSize] = level;
            level.heapIndex = heapSize;
            siftUp(heapSize++);
        }

        private void removeAt(int index) {
            int lastIndex = --heapSize;
            PriceLevel removed = heap[index];
            PriceLevel replacement = heap[lastIndex];
            heap[lastIndex] = null;
            removed.heapIndex = -1;

            if (index == lastIndex) {
                return;
            }

            heap[index] = replacement;
            replacement.heapIndex = index;
            if (index > 0 && better(heap[index], heap[(index - 1) >>> 1])) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (!better(heap[index], heap[parent])) {
                    return;
                }
                swap(index, parent);
                index = parent;
            }
        }

        private void siftDown(int index) {
            while (true) {
                int left = (index << 1) + 1;
                if (left >= heapSize) {
                    return;
                }

                int bestChild = left;
                int right = left + 1;
                if (right < heapSize && better(heap[right], heap[left])) {
                    bestChild = right;
                }

                if (!better(heap[bestChild], heap[index])) {
                    return;
                }

                swap(index, bestChild);
                index = bestChild;
            }
        }

        private boolean better(PriceLevel left, PriceLevel right) {
            return buySide ? left.price > right.price : left.price < right.price;
        }

        private void swap(int left, int right) {
            PriceLevel leftLevel = heap[left];
            PriceLevel rightLevel = heap[right];
            heap[left] = rightLevel;
            heap[right] = leftLevel;
            leftLevel.heapIndex = right;
            rightLevel.heapIndex = left;
        }
    }

    private static final class PriceLevel {
        private final SideBook book;
        private final long price;
        private int heapIndex = -1;
        private RestingOrder head;
        private RestingOrder tail;

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
