package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
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
                orders.add(new Order(order.id, level.book.side(), order.price, order.quantity));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static final class SideBook {
        private static final int INITIAL_HEAP_CAPACITY = 256;
        private static final int HEAP_ARITY = 5;

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
        private static final int BUCKET_SIZE = 4;

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
            int buckets = 1;
            int minCapacity = Math.max(capacity, DEFAULT_CAPACITY);
            while (buckets * BUCKET_SIZE < minCapacity) {
                buckets <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[buckets * BUCKET_SIZE];
            values = new Object[buckets * BUCKET_SIZE];
            resizeThreshold = (int) (values.length * loadFactor);
        }

        private V get(long key) {
            int index = bucketStart(key);
            while (true) {
                Object value0 = values[index];
                if (value0 == null) {
                    return null;
                }
                if (keys[index] == key) {
                    return valueAt(index);
                }

                Object value1 = values[index + 1];
                if (value1 == null) {
                    return null;
                }
                if (keys[index + 1] == key) {
                    return valueAt(index + 1);
                }

                Object value2 = values[index + 2];
                if (value2 == null) {
                    return null;
                }
                if (keys[index + 2] == key) {
                    return valueAt(index + 2);
                }

                Object value3 = values[index + 3];
                if (value3 == null) {
                    return null;
                }
                if (keys[index + 3] == key) {
                    return valueAt(index + 3);
                }

                index = nextBucket(index);
            }
        }

        private V put(long key, V value) {
            if (size >= resizeThreshold) {
                resize();
            }

            int index = bucketStart(key);
            while (true) {
                Object current0 = values[index];
                if (current0 == null) {
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

                Object current1 = values[index + 1];
                if (current1 == null) {
                    keys[index + 1] = key;
                    values[index + 1] = value;
                    size++;
                    return null;
                }
                if (keys[index + 1] == key) {
                    V previous = valueAt(index + 1);
                    values[index + 1] = value;
                    return previous;
                }

                Object current2 = values[index + 2];
                if (current2 == null) {
                    keys[index + 2] = key;
                    values[index + 2] = value;
                    size++;
                    return null;
                }
                if (keys[index + 2] == key) {
                    V previous = valueAt(index + 2);
                    values[index + 2] = value;
                    return previous;
                }

                Object current3 = values[index + 3];
                if (current3 == null) {
                    keys[index + 3] = key;
                    values[index + 3] = value;
                    size++;
                    return null;
                }
                if (keys[index + 3] == key) {
                    V previous = valueAt(index + 3);
                    values[index + 3] = value;
                    return previous;
                }

                index = nextBucket(index);
            }
        }

        private V remove(long key) {
            int index = bucketStart(key);
            while (true) {
                Object current0 = values[index];
                if (current0 == null) {
                    return null;
                }
                if (keys[index] == key) {
                    V removed = valueAt(index);
                    deleteIndex(index);
                    return removed;
                }

                Object current1 = values[index + 1];
                if (current1 == null) {
                    return null;
                }
                if (keys[index + 1] == key) {
                    V removed = valueAt(index + 1);
                    deleteIndex(index + 1);
                    return removed;
                }

                Object current2 = values[index + 2];
                if (current2 == null) {
                    return null;
                }
                if (keys[index + 2] == key) {
                    V removed = valueAt(index + 2);
                    deleteIndex(index + 2);
                    return removed;
                }

                Object current3 = values[index + 3];
                if (current3 == null) {
                    return null;
                }
                if (keys[index + 3] == key) {
                    V removed = valueAt(index + 3);
                    deleteIndex(index + 3);
                    return removed;
                }

                index = nextBucket(index);
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
            size--;
            values[index] = null;

            int next = nextIndex(index);
            while (values[next] != null) {
                long key = keys[next];
                Object value = values[next];
                values[next] = null;
                size--;
                reinsert(key, value);
                next = nextIndex(next);
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
            insertRehashed(key, value);
        }

        private int mix(long key) {
            long mixed = key ^ (key >>> 33);
            mixed ^= mixed >>> 17;
            return (int) mixed;
        }

        private int bucketStart(long key) {
            int bucketMask = (values.length / BUCKET_SIZE) - 1;
            return (mix(key) & bucketMask) * BUCKET_SIZE;
        }

        private int nextBucket(int index) {
            index += BUCKET_SIZE;
            return index == values.length ? 0 : index;
        }

        private int nextIndex(int index) {
            index++;
            return index == values.length ? 0 : index;
        }

        private void insertRehashed(long key, Object value) {
            int index = bucketStart(key);
            while (true) {
                if (values[index] == null) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    return;
                }
                if (values[index + 1] == null) {
                    keys[index + 1] = key;
                    values[index + 1] = value;
                    size++;
                    return;
                }
                if (values[index + 2] == null) {
                    keys[index + 2] = key;
                    values[index + 2] = value;
                    size++;
                    return;
                }
                if (values[index + 3] == null) {
                    keys[index + 3] = key;
                    values[index + 3] = value;
                    size++;
                    return;
                }
                index = nextBucket(index);
            }
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
        private static final int BUCKET_SIZE = 4;

        private long[] keys;
        private RestingOrder[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

        private LongOrderMap(int capacity) {
            this(capacity, 0.6f);
        }

        private LongOrderMap(int capacity, float loadFactor) {
            int buckets = 1;
            while (buckets * BUCKET_SIZE < capacity) {
                buckets <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[buckets * BUCKET_SIZE];
            values = new RestingOrder[buckets * BUCKET_SIZE];
            resizeThreshold = (int) (values.length * loadFactor);
        }

        private int size() {
            return size;
        }

        private RestingOrder get(long key) {
            int index = bucketStart(key);
            while (true) {
                RestingOrder value0 = values[index];
                if (value0 == null) {
                    return null;
                }
                if (keys[index] == key) {
                    return value0;
                }

                RestingOrder value1 = values[index + 1];
                if (value1 == null) {
                    return null;
                }
                if (keys[index + 1] == key) {
                    return value1;
                }

                RestingOrder value2 = values[index + 2];
                if (value2 == null) {
                    return null;
                }
                if (keys[index + 2] == key) {
                    return value2;
                }

                RestingOrder value3 = values[index + 3];
                if (value3 == null) {
                    return null;
                }
                if (keys[index + 3] == key) {
                    return value3;
                }

                index = nextBucket(index);
            }
        }

        private RestingOrder put(long key, RestingOrder value) {
            if (size >= resizeThreshold) {
                resize();
            }

            int index = bucketStart(key);
            while (true) {
                RestingOrder current0 = values[index];
                if (current0 == null) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    return null;
                }
                if (keys[index] == key) {
                    values[index] = value;
                    return current0;
                }

                RestingOrder current1 = values[index + 1];
                if (current1 == null) {
                    keys[index + 1] = key;
                    values[index + 1] = value;
                    size++;
                    return null;
                }
                if (keys[index + 1] == key) {
                    values[index + 1] = value;
                    return current1;
                }

                RestingOrder current2 = values[index + 2];
                if (current2 == null) {
                    keys[index + 2] = key;
                    values[index + 2] = value;
                    size++;
                    return null;
                }
                if (keys[index + 2] == key) {
                    values[index + 2] = value;
                    return current2;
                }

                RestingOrder current3 = values[index + 3];
                if (current3 == null) {
                    keys[index + 3] = key;
                    values[index + 3] = value;
                    size++;
                    return null;
                }
                if (keys[index + 3] == key) {
                    values[index + 3] = value;
                    return current3;
                }

                index = nextBucket(index);
            }
        }

        private RestingOrder remove(long key) {
            int index = bucketStart(key);
            while (true) {
                RestingOrder current0 = values[index];
                if (current0 == null) {
                    return null;
                }
                if (keys[index] == key) {
                    RestingOrder removed = current0;
                    deleteIndex(index);
                    return removed;
                }

                RestingOrder current1 = values[index + 1];
                if (current1 == null) {
                    return null;
                }
                if (keys[index + 1] == key) {
                    RestingOrder removed = current1;
                    deleteIndex(index + 1);
                    return removed;
                }

                RestingOrder current2 = values[index + 2];
                if (current2 == null) {
                    return null;
                }
                if (keys[index + 2] == key) {
                    RestingOrder removed = current2;
                    deleteIndex(index + 2);
                    return removed;
                }

                RestingOrder current3 = values[index + 3];
                if (current3 == null) {
                    return null;
                }
                if (keys[index + 3] == key) {
                    RestingOrder removed = current3;
                    deleteIndex(index + 3);
                    return removed;
                }

                index = nextBucket(index);
            }
        }

        private void deleteIndex(int index) {
            size--;
            values[index] = null;

            int next = nextIndex(index);
            while (values[next] != null) {
                long key = keys[next];
                RestingOrder value = values[next];
                values[next] = null;
                size--;
                reinsert(key, value);
                next = nextIndex(next);
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
            insertRehashed(key, value);
        }

        private int mix(long key) {
            long mixed = key ^ (key >>> 33);
            mixed ^= mixed >>> 17;
            return (int) mixed;
        }

        private int bucketStart(long key) {
            int bucketMask = (values.length / BUCKET_SIZE) - 1;
            return (mix(key) & bucketMask) * BUCKET_SIZE;
        }

        private int nextBucket(int index) {
            index += BUCKET_SIZE;
            return index == values.length ? 0 : index;
        }

        private int nextIndex(int index) {
            index++;
            return index == values.length ? 0 : index;
        }

        private void insertRehashed(long key, RestingOrder value) {
            int index = bucketStart(key);
            while (true) {
                if (values[index] == null) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    return;
                }
                if (values[index + 1] == null) {
                    keys[index + 1] = key;
                    values[index + 1] = value;
                    size++;
                    return;
                }
                if (values[index + 2] == null) {
                    keys[index + 2] = key;
                    values[index + 2] = value;
                    size++;
                    return;
                }
                if (values[index + 3] == null) {
                    keys[index + 3] = key;
                    values[index + 3] = value;
                    size++;
                    return;
                }
                index = nextBucket(index);
            }
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
        private final long price;
        private long quantity;
        private final PriceLevel level;
        private RestingOrder prev;
        private RestingOrder next;

        private RestingOrder(long id, long price, long quantity, PriceLevel level) {
            this.id = id;
            this.price = price;
            this.quantity = quantity;
            this.level = level;
        }
    }
}
