package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private static final int NO_ORDER = -1;
    private static final byte BUY = 1;
    private static final byte SELL = 2;

    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
    private final LongIntMap orderSlotById = new LongIntMap(4096);
    private final OrderMatchListener listener;

    private long[] orderIds = new long[1024];
    private long[] orderPrices = new long[1024];
    private long[] orderQuantities = new long[1024];
    private byte[] orderSides = new byte[1024];
    private PriceLevel[] orderLevels = new PriceLevel[1024];
    private int[] prevOrders = new int[1024];
    private int[] nextOrders = new int[1024];
    private int orderCapacity = 1024;
    private int nextOrderSlot;
    private int freeOrderSlot = NO_ORDER;

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

        int orderSlot = allocateOrderSlot();
        orderIds[orderSlot] = id;
        orderPrices[orderSlot] = price;
        orderQuantities[orderSlot] = remainingQuantity;
        orderSides[orderSlot] = side == Order.Side.BUY ? BUY : SELL;
        orderLevels[orderSlot] = level;
        appendOrder(level, orderSlot);
        orderSlotById.put(id, orderSlot);
    }

    @Override
    public void cancelOrder(long id) {
        int orderSlot = removeTrackedOrder(id);
        removeOrder(orderSlot);
        releaseOrderSlot(orderSlot);
    }

    @Override
    public void modifyOrder(long id, long newPrice, long newQuantity) {
        int orderSlot = removeTrackedOrder(id);
        Order.Side side = toSide(orderSides[orderSlot]);
        removeOrder(orderSlot);
        releaseOrderSlot(orderSlot);
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

            int makerSlot = level.head;
            while (makerSlot != NO_ORDER && remainingQuantity > 0) {
                int nextMakerSlot = nextOrders[makerSlot];
                long matchedQuantity = Math.min(remainingQuantity, orderQuantities[makerSlot]);
                listener.onMatch(orderIds[makerSlot], incomingId, orderPrices[makerSlot], matchedQuantity);

                remainingQuantity -= matchedQuantity;
                long remainingMakerQuantity = orderQuantities[makerSlot] - matchedQuantity;
                if (remainingMakerQuantity == 0) {
                    orderSlotById.remove(orderIds[makerSlot]);
                    removeOrder(makerSlot);
                    releaseOrderSlot(makerSlot);
                } else {
                    orderQuantities[makerSlot] = remainingMakerQuantity;
                }
                makerSlot = nextMakerSlot;
            }
        }

        return remainingQuantity;
    }

    private boolean crosses(Order.Side incomingSide, long incomingPrice, long restingPrice) {
        return incomingSide == Order.Side.BUY
                ? incomingPrice >= restingPrice
                : incomingPrice <= restingPrice;
    }

    private int removeTrackedOrder(long id) {
        int orderSlot = orderSlotById.remove(id);
        if (orderSlot == NO_ORDER) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }
        return orderSlot;
    }

    private void appendOrder(PriceLevel level, int orderSlot) {
        int tail = level.tail;
        prevOrders[orderSlot] = tail;
        nextOrders[orderSlot] = NO_ORDER;
        if (tail == NO_ORDER) {
            level.head = orderSlot;
            level.tail = orderSlot;
            return;
        }

        nextOrders[tail] = orderSlot;
        level.tail = orderSlot;
    }

    private void removeOrder(int orderSlot) {
        PriceLevel level = orderLevels[orderSlot];
        int prevOrderSlot = prevOrders[orderSlot];
        int nextOrderSlot = nextOrders[orderSlot];

        if (prevOrderSlot == NO_ORDER) {
            level.head = nextOrderSlot;
        } else {
            nextOrders[prevOrderSlot] = nextOrderSlot;
        }

        if (nextOrderSlot == NO_ORDER) {
            level.tail = prevOrderSlot;
        } else {
            prevOrders[nextOrderSlot] = prevOrderSlot;
        }

        orderLevels[orderSlot] = null;
        prevOrders[orderSlot] = NO_ORDER;
        nextOrders[orderSlot] = NO_ORDER;
        if (level.isEmpty()) {
            level.book.removeLevel(level);
        }
    }

    private int allocateOrderSlot() {
        int orderSlot = freeOrderSlot;
        if (orderSlot != NO_ORDER) {
            freeOrderSlot = nextOrders[orderSlot];
            nextOrders[orderSlot] = NO_ORDER;
            prevOrders[orderSlot] = NO_ORDER;
            return orderSlot;
        }

        orderSlot = nextOrderSlot++;
        if (orderSlot == orderCapacity) {
            growOrderStorage();
        }
        return orderSlot;
    }

    private void releaseOrderSlot(int orderSlot) {
        nextOrders[orderSlot] = freeOrderSlot;
        prevOrders[orderSlot] = NO_ORDER;
        orderLevels[orderSlot] = null;
        orderSides[orderSlot] = 0;
        freeOrderSlot = orderSlot;
    }

    private void growOrderStorage() {
        int newCapacity = orderCapacity << 1;
        orderIds = copyOf(orderIds, newCapacity);
        orderPrices = copyOf(orderPrices, newCapacity);
        orderQuantities = copyOf(orderQuantities, newCapacity);
        orderSides = copyOf(orderSides, newCapacity);
        orderLevels = copyOf(orderLevels, newCapacity);
        prevOrders = copyOf(prevOrders, newCapacity);
        nextOrders = copyOf(nextOrders, newCapacity);
        orderCapacity = newCapacity;
    }

    private List<Order> snapshot(SideBook book) {
        List<PriceLevel> levels = book.snapshotLevels();
        List<Order> orders = new ArrayList<>(orderSlotById.size());
        for (PriceLevel level : levels) {
            for (int orderSlot = level.head; orderSlot != NO_ORDER; orderSlot = nextOrders[orderSlot]) {
                orders.add(new Order(
                        orderIds[orderSlot],
                        toSide(orderSides[orderSlot]),
                        orderPrices[orderSlot],
                        orderQuantities[orderSlot]
                ));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private Order.Side toSide(byte side) {
        return side == BUY ? Order.Side.BUY : Order.Side.SELL;
    }

    private long[] copyOf(long[] source, int newLength) {
        long[] copy = new long[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private byte[] copyOf(byte[] source, int newLength) {
        byte[] copy = new byte[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private int[] copyOf(int[] source, int newLength) {
        int[] copy = new int[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private PriceLevel[] copyOf(PriceLevel[] source, int newLength) {
        PriceLevel[] copy = new PriceLevel[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
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
            removed.heapIndex = NO_ORDER;

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
    }

    private static final class LongObjectMap<V> {
        private long[] keys;
        private Object[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

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

    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

        private LongIntMap(int capacity) {
            this(capacity, 0.6f);
        }

        private LongIntMap(int capacity, float loadFactor) {
            int actualCapacity = 1;
            while (actualCapacity < capacity) {
                actualCapacity <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[actualCapacity];
            values = new int[actualCapacity];
            resizeThreshold = (int) (actualCapacity * loadFactor);
        }

        private int size() {
            return size;
        }

        private int get(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int value = values[index];
                if (value == 0) {
                    return NO_ORDER;
                }
                if (keys[index] == key) {
                    return value - 1;
                }
                index = (index + 1) & mask;
            }
        }

        private int put(long key, int value) {
            if (size >= resizeThreshold) {
                resize();
            }

            int storedValue = value + 1;
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int current = values[index];
                if (current == 0) {
                    keys[index] = key;
                    values[index] = storedValue;
                    size++;
                    return NO_ORDER;
                }
                if (keys[index] == key) {
                    values[index] = storedValue;
                    return current - 1;
                }
                index = (index + 1) & mask;
            }
        }

        private int remove(long key) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int current = values[index];
                if (current == 0) {
                    return NO_ORDER;
                }
                if (keys[index] == key) {
                    int removed = current - 1;
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
                int value = values[next];
                if (value == 0) {
                    values[gap] = 0;
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
            int[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new int[oldValues.length << 1];
            resizeThreshold = (int) (values.length * loadFactor);

            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                int value = oldValues[i];
                if (value != 0) {
                    reinsert(oldKeys[i], value);
                }
            }
            size = oldSize;
        }

        private void reinsert(long key, int value) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (values[index] != 0) {
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
        private final SideBook book;
        private final long price;
        private int heapIndex = NO_ORDER;
        private int head = NO_ORDER;
        private int tail = NO_ORDER;

        private PriceLevel(SideBook book, long price) {
            this.book = book;
            this.price = price;
        }

        private boolean isEmpty() {
            return head == NO_ORDER;
        }
    }
}
