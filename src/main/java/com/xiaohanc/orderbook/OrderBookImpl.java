package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private final DenseSideBook bids = new DenseSideBook(true);
    private final DenseSideBook asks = new DenseSideBook(false);
    private final LongOrderMap orderById = new LongOrderMap(16384);
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

        DenseSideBook book = side == Order.Side.BUY ? bids : asks;
        int index = book.ensureIndex(price);
        RestingOrder order = new RestingOrder(id, remainingQuantity, book, index);
        book.append(order);
        orderById.put(id, order);
    }

    @Override
    public void cancelOrder(long id) {
        RestingOrder order = orderById.remove(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        order.book.remove(order);
    }

    @Override
    public void modifyOrder(long id, long newPrice, long newQuantity) {
        RestingOrder order = orderById.get(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        Order.Side side = order.side();
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
        DenseSideBook oppositeBook = incomingSide == Order.Side.BUY ? asks : bids;
        long remainingQuantity = incomingQuantity;

        while (remainingQuantity > 0) {
            RestingOrder maker = oppositeBook.bestHead();
            if (maker == null || !crosses(incomingSide, incomingPrice, maker.price())) {
                break;
            }

            long matchedPrice = maker.price();
            while (maker != null && remainingQuantity > 0) {
                RestingOrder nextMaker = maker.next;
                long matchedQuantity = Math.min(remainingQuantity, maker.quantity);
                listener.onMatch(maker.id, incomingId, matchedPrice, matchedQuantity);

                remainingQuantity -= matchedQuantity;
                maker.quantity -= matchedQuantity;
                if (maker.quantity == 0) {
                    orderById.remove(maker.id);
                    oppositeBook.remove(maker);
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

    private List<Order> snapshot(DenseSideBook book) {
        List<Order> orders = new ArrayList<>(orderById.size());
        for (int index = book.firstActiveIndex(); index >= 0; index = book.nextActiveIndex(index)) {
            for (RestingOrder order = book.headAt(index); order != null; order = order.next) {
                orders.add(new Order(order.id, order.side(), order.price(), order.quantity));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static final class DenseSideBook {
        private static final int INITIAL_WINDOW = 1024;
        private static final RestingOrder[] EMPTY_ORDERS = new RestingOrder[0];
        private static final long[] EMPTY_BITS = new long[0];

        private final boolean buySide;
        private long basePrice;
        private RestingOrder[] heads = EMPTY_ORDERS;
        private RestingOrder[] tails = EMPTY_ORDERS;
        private long[] active = EMPTY_BITS;
        private int bestIndex = -1;

        private DenseSideBook(boolean buySide) {
            this.buySide = buySide;
        }

        private RestingOrder bestHead() {
            return bestIndex < 0 ? null : heads[bestIndex];
        }

        private RestingOrder headAt(int index) {
            return heads[index];
        }

        private int ensureIndex(long price) {
            if (heads.length == 0) {
                initialize(price);
            } else if (price < basePrice || price >= basePrice + heads.length) {
                expandFor(price);
            }
            return (int) (price - basePrice);
        }

        private void append(RestingOrder order) {
            int index = order.index;
            RestingOrder tail = tails[index];
            if (tail == null) {
                heads[index] = order;
                tails[index] = order;
                setActive(index);
                if (bestIndex < 0 || better(index, bestIndex)) {
                    bestIndex = index;
                }
                return;
            }

            tail.next = order;
            order.prev = tail;
            tails[index] = order;
        }

        private void remove(RestingOrder order) {
            int index = order.index;
            RestingOrder prev = order.prev;
            RestingOrder next = order.next;

            if (prev == null) {
                heads[index] = next;
            } else {
                prev.next = next;
            }

            if (next == null) {
                tails[index] = prev;
            } else {
                next.prev = prev;
            }

            order.prev = null;
            order.next = null;

            if (heads[index] == null) {
                clearActive(index);
                if (index == bestIndex) {
                    bestIndex = buySide ? previousSetBit(index - 1) : nextSetBit(index + 1);
                }
            }
        }

        private int firstActiveIndex() {
            return buySide ? bestIndex : nextSetBit(0);
        }

        private int nextActiveIndex(int current) {
            return buySide ? previousSetBit(current - 1) : nextSetBit(current + 1);
        }

        private long priceAt(int index) {
            return basePrice + index;
        }

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }

        private void initialize(long price) {
            int length = INITIAL_WINDOW;
            long half = length >>> 1;
            basePrice = price - half;
            heads = new RestingOrder[length];
            tails = new RestingOrder[length];
            active = new long[(length + Long.SIZE - 1) >>> 6];
        }

        private void expandFor(long price) {
            long currentMin = basePrice;
            long currentMax = basePrice + heads.length - 1L;
            long min = Math.min(currentMin, price);
            long max = Math.max(currentMax, price);

            int newLength = heads.length;
            long required = max - min + 1L;
            while (newLength < required) {
                newLength <<= 1;
            }

            long slack = newLength - required;
            long newBase = min - (slack >>> 1);
            int shift = (int) (basePrice - newBase);

            RestingOrder[] newHeads = new RestingOrder[newLength];
            RestingOrder[] newTails = new RestingOrder[newLength];
            System.arraycopy(heads, 0, newHeads, shift, heads.length);
            System.arraycopy(tails, 0, newTails, shift, tails.length);

            long[] newActive = new long[(newLength + Long.SIZE - 1) >>> 6];
            for (int i = 0; i < heads.length; i++) {
                RestingOrder head = heads[i];
                if (head == null) {
                    continue;
                }

                int shiftedIndex = i + shift;
                setBit(newActive, shiftedIndex);
                for (RestingOrder order = head; order != null; order = order.next) {
                    order.index = shiftedIndex;
                }
            }

            basePrice = newBase;
            heads = newHeads;
            tails = newTails;
            active = newActive;
            if (bestIndex >= 0) {
                bestIndex += shift;
            }
        }

        private boolean better(int leftIndex, int rightIndex) {
            return buySide ? leftIndex > rightIndex : leftIndex < rightIndex;
        }

        private void setActive(int index) {
            setBit(active, index);
        }

        private void clearActive(int index) {
            clearBit(active, index);
        }

        private int nextSetBit(int fromIndex) {
            if (fromIndex < 0) {
                fromIndex = 0;
            }
            if (fromIndex >= heads.length) {
                return -1;
            }

            int wordIndex = fromIndex >>> 6;
            long word = active[wordIndex] & (-1L << (fromIndex & 63));
            while (true) {
                if (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int index = (wordIndex << 6) + bit;
                    return index < heads.length ? index : -1;
                }
                wordIndex++;
                if (wordIndex >= active.length) {
                    return -1;
                }
                word = active[wordIndex];
            }
        }

        private int previousSetBit(int fromIndex) {
            if (fromIndex >= heads.length) {
                fromIndex = heads.length - 1;
            }
            if (fromIndex < 0) {
                return -1;
            }

            int wordIndex = fromIndex >>> 6;
            long word = active[wordIndex] & (-1L >>> (63 - (fromIndex & 63)));
            while (true) {
                if (word != 0L) {
                    int bit = 63 - Long.numberOfLeadingZeros(word);
                    return (wordIndex << 6) + bit;
                }
                if (wordIndex == 0) {
                    return -1;
                }
                wordIndex--;
                word = active[wordIndex];
            }
        }

        private static void setBit(long[] bits, int index) {
            bits[index >>> 6] |= 1L << (index & 63);
        }

        private static void clearBit(long[] bits, int index) {
            bits[index >>> 6] &= ~(1L << (index & 63));
        }
    }

    private static final class LongOrderMap {
        private long[] keys;
        private RestingOrder[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

        private LongOrderMap(int capacity) {
            this(capacity, 0.6f);
        }

        private LongOrderMap(int capacity, float loadFactor) {
            int actualCapacity = 1;
            while (actualCapacity < capacity) {
                actualCapacity <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[actualCapacity];
            values = new RestingOrder[actualCapacity];
            resizeThreshold = (int) (actualCapacity * loadFactor);
        }

        private int size() {
            return size;
        }

        private RestingOrder get(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                RestingOrder value = values[index];
                if (value == null) {
                    return null;
                }
                if (keys[index] == key) {
                    return value;
                }
                index = (index + 1) & mask;
            }
        }

        private RestingOrder put(long key, RestingOrder value) {
            if (size >= resizeThreshold) {
                resize();
            }

            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                RestingOrder current = values[index];
                if (current == null) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    return null;
                }
                if (keys[index] == key) {
                    values[index] = value;
                    return current;
                }
                index = (index + 1) & mask;
            }
        }

        private RestingOrder remove(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                RestingOrder current = values[index];
                if (current == null) {
                    return null;
                }
                if (keys[index] == key) {
                    RestingOrder removed = current;
                    deleteIndex(index);
                    return removed;
                }
                index = (index + 1) & mask;
            }
        }

        private void deleteIndex(int index) {
            int mask = values.length - 1;
            size--;
            int gap = index;
            int next = (index + 1) & mask;
            while (true) {
                RestingOrder value = values[next];
                if (value == null) {
                    values[gap] = null;
                    return;
                }

                int home = mix(keys[next]) & mask;
                if (((next - home) & mask) >= ((gap - home) & mask)) {
                    keys[gap] = keys[next];
                    values[gap] = value;
                    gap = next;
                }
                next = (next + 1) & mask;
            }
        }

        private void resize() {
            long[] oldKeys = keys;
            RestingOrder[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new RestingOrder[oldValues.length << 1];
            resizeThreshold = (int) (values.length * loadFactor);

            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                RestingOrder value = oldValues[i];
                if (value != null) {
                    reinsert(oldKeys[i], value);
                }
            }
            size = oldSize;
        }

        private void reinsert(long key, RestingOrder value) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (values[index] != null) {
                index = (index + 1) & mask;
            }
            keys[index] = key;
            values[index] = value;
            size++;
        }

        private int mix(long key) {
            long mixed = key ^ (key >>> 33);
            mixed ^= mixed >>> 17;
            return (int) mixed;
        }
    }

    private static final class RestingOrder {
        private final long id;
        private long quantity;
        private final DenseSideBook book;
        private int index;
        private RestingOrder prev;
        private RestingOrder next;

        private RestingOrder(long id, long quantity, DenseSideBook book, int index) {
            this.id = id;
            this.quantity = quantity;
            this.book = book;
            this.index = index;
        }

        private long price() {
            return book.priceAt(index);
        }

        private Order.Side side() {
            return book.side();
        }
    }
}
