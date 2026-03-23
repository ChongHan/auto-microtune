package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
    private final LongOrderMap orderById = new LongOrderMap(4096);
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

        RestingOrder order = new RestingOrder(id, price, remainingQuantity, level);
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

        Order.Side side = order.level.book.side();
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
            if (level == null) {
                break;
            }

            level.prepareForAccess();
            if (level.isEmpty()) {
                oppositeBook.removeLevel(level);
                continue;
            }

            if (!crosses(incomingSide, incomingPrice, level.price)) {
                break;
            }

            while (remainingQuantity > 0) {
                RestingOrder maker = level.head;
                if (maker == null) {
                    oppositeBook.removeLevel(level);
                    break;
                }

                long matchedQuantity = Math.min(remainingQuantity, maker.quantity);
                listener.onMatch(maker.id, incomingId, maker.price, matchedQuantity);

                remainingQuantity -= matchedQuantity;
                maker.quantity -= matchedQuantity;
                if (maker.quantity == 0) {
                    orderById.remove(maker.id);
                    level.removeMatchedHead();
                    if (level.isEmpty()) {
                        oppositeBook.removeLevel(level);
                        break;
                    }
                }
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
        level.tombstone(order);
        if (level.isEmpty()) {
            level.book.removeLevel(level);
        }
    }

    private List<Order> snapshot(SideBook book) {
        List<PriceLevel> levels = book.snapshotLevels();
        List<Order> orders = new ArrayList<>(orderById.size());
        for (PriceLevel level : levels) {
            level.prepareForSnapshot();
            if (level.isEmpty()) {
                level.book.removeLevel(level);
                continue;
            }
            for (RestingOrder order = level.head; order != null; order = order.next) {
                if (order.quantity != 0) {
                    orders.add(new Order(order.id, level.book.side(), order.price, order.quantity));
                }
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static final class SideBook {
        private static final int INITIAL_HEAP_CAPACITY = 256;
        private static final int HEAP_ARITY = 4;

        private final boolean buySide;
        private final LongObjectMap<PriceLevel> levels = new LongObjectMap<>(256, 0.5f);
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
            List<PriceLevel> orderedLevels = new ArrayList<>(levels.size());
            levels.addValuesTo(orderedLevels);
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
            if (index > 0 && better(heap[index], heap[(index - 1) / HEAP_ARITY])) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) / HEAP_ARITY;
                if (!better(heap[index], heap[parent])) {
                    return;
                }
                swap(index, parent);
                index = parent;
            }
        }

        private void siftDown(int index) {
            while (true) {
                int firstChild = index * HEAP_ARITY + 1;
                if (firstChild >= heapSize) {
                    return;
                }

                int bestChild = firstChild;
                int childLimit = Math.min(firstChild + HEAP_ARITY, heapSize);
                for (int child = firstChild + 1; child < childLimit; child++) {
                    if (better(heap[child], heap[bestChild])) {
                        bestChild = child;
                    }
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

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }
    }

    private static final class LongObjectMap<V> {
        private static final int DEFAULT_CAPACITY = 16;

        private long[] keys;
        private Object[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

        private LongObjectMap() {
            this(DEFAULT_CAPACITY, 0.6f);
        }

        private LongObjectMap(int capacity) {
            this(capacity, 0.6f);
        }

        private LongObjectMap(int capacity, float loadFactor) {
            int actualCapacity = 1;
            while (actualCapacity < capacity) {
                actualCapacity <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[actualCapacity];
            values = new Object[actualCapacity];
            resizeThreshold = (int) (actualCapacity * loadFactor);
        }

        private V get(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                Object value = values[index];
                if (value == null) {
                    return null;
                }
                if (keys[index] == key) {
                    return valueAt(index);
                }
                index = (index + 1) & mask;
            }
        }

        private V put(long key, V value) {
            if (size >= resizeThreshold) {
                resize();
            }

            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                Object current = values[index];
                if (current == null) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    return null;
                }
                if (keys[index] == key) {
                    V previous = valueAt(index);
                    values[index] = value;
                    return previous;
                }
                index = (index + 1) & mask;
            }
        }

        private V remove(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                Object current = values[index];
                if (current == null) {
                    return null;
                }
                if (keys[index] == key) {
                    V removed = valueAt(index);
                    deleteIndex(index);
                    return removed;
                }
                index = (index + 1) & mask;
            }
        }

        private int size() {
            return size;
        }

        private void addValuesTo(List<V> out) {
            for (Object value : values) {
                if (value != null) {
                    out.add(cast(value));
                }
            }
        }

        private void deleteIndex(int index) {
            int mask = values.length - 1;
            size--;
            int gap = index;
            int next = (index + 1) & mask;
            while (true) {
                Object value = values[next];
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
            Object[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new Object[oldValues.length << 1];
            resizeThreshold = (int) (values.length * loadFactor);

            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                Object value = oldValues[i];
                if (value != null) {
                    reinsert(oldKeys[i], value);
                }
            }
            size = oldSize;
        }

        private void reinsert(long key, Object value) {
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

        @SuppressWarnings("unchecked")
        private V valueAt(int index) {
            return (V) values[index];
        }

        @SuppressWarnings("unchecked")
        private V cast(Object value) {
            return (V) value;
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

    private static final class PriceLevel {
        private static final int COMPACT_THRESHOLD = 8;

        private final SideBook book;
        private final long price;
        private int heapIndex = -1;
        private RestingOrder head;
        private RestingOrder tail;
        private int liveOrderCount;
        private int deadOrderCount;

        private PriceLevel(SideBook book, long price) {
            this.book = book;
            this.price = price;
        }

        private void append(RestingOrder order) {
            order.next = null;
            if (tail == null) {
                head = order;
                tail = order;
            } else {
                tail.next = order;
                tail = order;
            }
            liveOrderCount++;
        }

        private void tombstone(RestingOrder order) {
            order.quantity = 0;
            liveOrderCount--;
            if (liveOrderCount == 0) {
                clear();
                return;
            }

            deadOrderCount++;
            if (order == head) {
                pruneDeadFront();
                return;
            }

            maybeCompact();
        }

        private void removeMatchedHead() {
            RestingOrder matched = head;
            head = matched.next;
            matched.next = null;
            liveOrderCount--;
            if (liveOrderCount == 0) {
                clear();
                return;
            }
            if (head == null) {
                tail = null;
                return;
            }
            pruneDeadFront();
            maybeCompact();
        }

        private void prepareForAccess() {
            pruneDeadFront();
            maybeCompact();
        }

        private void prepareForSnapshot() {
            if (deadOrderCount != 0) {
                compact();
            }
        }

        private void pruneDeadFront() {
            while (head != null && head.quantity == 0) {
                RestingOrder removed = head;
                head = removed.next;
                removed.next = null;
                deadOrderCount--;
            }
            if (head == null) {
                tail = null;
            }
        }

        private void maybeCompact() {
            if (deadOrderCount >= COMPACT_THRESHOLD && deadOrderCount > liveOrderCount) {
                compact();
            }
        }

        private void compact() {
            RestingOrder newHead = null;
            RestingOrder newTail = null;
            RestingOrder current = head;
            while (current != null) {
                RestingOrder next = current.next;
                if (current.quantity != 0) {
                    if (newTail == null) {
                        newHead = current;
                    } else {
                        newTail.next = current;
                    }
                    newTail = current;
                    current.next = null;
                } else {
                    current.next = null;
                }
                current = next;
            }
            head = newHead;
            tail = newTail;
            deadOrderCount = 0;
        }

        private void clear() {
            head = null;
            tail = null;
            liveOrderCount = 0;
            deadOrderCount = 0;
        }

        private boolean isEmpty() {
            return liveOrderCount == 0;
        }
    }

    private static final class RestingOrder {
        private final long id;
        private final long price;
        private long quantity;
        private final PriceLevel level;
        private RestingOrder next;

        private RestingOrder(long id, long price, long quantity, PriceLevel level) {
            this.id = id;
            this.price = price;
            this.quantity = quantity;
            this.level = level;
        }
    }
}
