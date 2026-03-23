package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private static final int NO_INDEX = -1;
    private static final byte BUY_SIDE = 1;

    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
    private final LongIntMap orderById = new LongIntMap(16384, 0.6f, NO_INDEX);
    private final OrderMatchListener listener;

    private long[] orderIds = new long[1024];
    private long[] orderQuantities = new long[1024];
    private int[] orderLevels = filledIntArray(1024, NO_INDEX);
    private int[] orderPrev = filledIntArray(1024, NO_INDEX);
    private int[] orderNext = filledIntArray(1024, NO_INDEX);
    private int[] orderMapSlots = filledIntArray(1024, NO_INDEX);
    private byte[] orderSides = new byte[1024];
    private int orderCapacity = 1024;
    private int nextOrderSlot;
    private int freeOrderSlot = NO_INDEX;

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
        int levelSlot = book.level(price);
        if (levelSlot == NO_INDEX) {
            levelSlot = book.addLevel(price);
        }

        int orderSlot = allocateOrderSlot();
        orderIds[orderSlot] = id;
        orderQuantities[orderSlot] = remainingQuantity;
        orderLevels[orderSlot] = levelSlot;
        orderSides[orderSlot] = book.sideFlag;
        appendOrder(book, levelSlot, orderSlot);
        orderById.put(id, orderSlot, orderMapSlots);
    }

    @Override
    public void cancelOrder(long id) {
        int orderSlot = orderById.remove(id, orderMapSlots);
        if (orderSlot == NO_INDEX) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        removeOrder(orderSlot);
        releaseOrderSlot(orderSlot);
    }

    @Override
    public void modifyOrder(long id, long newPrice, long newQuantity) {
        int orderSlot = orderById.remove(id, orderMapSlots);
        if (orderSlot == NO_INDEX) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        Order.Side side = orderSides[orderSlot] == BUY_SIDE ? Order.Side.BUY : Order.Side.SELL;
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
            int levelSlot = oppositeBook.bestLevel();
            if (levelSlot == NO_INDEX) {
                break;
            }

            long matchedPrice = oppositeBook.levelPrices[levelSlot];
            if (!crosses(incomingSide, incomingPrice, matchedPrice)) {
                break;
            }

            int makerSlot = oppositeBook.levelHeads[levelSlot];
            while (makerSlot != NO_INDEX && remainingQuantity > 0) {
                int nextMakerSlot = orderNext[makerSlot];
                long matchedQuantity = Math.min(remainingQuantity, orderQuantities[makerSlot]);
                listener.onMatch(orderIds[makerSlot], incomingId, matchedPrice, matchedQuantity);

                remainingQuantity -= matchedQuantity;
                long remainingMakerQuantity = orderQuantities[makerSlot] - matchedQuantity;
                if (remainingMakerQuantity == 0) {
                    orderById.removeValue(makerSlot, orderMapSlots);
                    removeMatchedHead(oppositeBook, levelSlot, makerSlot, nextMakerSlot);
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

    private void removeOrder(int orderSlot) {
        SideBook book = orderSides[orderSlot] == BUY_SIDE ? bids : asks;
        int levelSlot = orderLevels[orderSlot];
        int prevOrderSlot = orderPrev[orderSlot];
        int nextOrderSlot = orderNext[orderSlot];

        if (prevOrderSlot == NO_INDEX) {
            book.levelHeads[levelSlot] = nextOrderSlot;
        } else {
            orderNext[prevOrderSlot] = nextOrderSlot;
        }

        if (nextOrderSlot == NO_INDEX) {
            book.levelTails[levelSlot] = prevOrderSlot;
        } else {
            orderPrev[nextOrderSlot] = prevOrderSlot;
        }

        orderPrev[orderSlot] = NO_INDEX;
        orderNext[orderSlot] = NO_INDEX;
        if (book.levelHeads[levelSlot] == NO_INDEX) {
            book.removeLevel(levelSlot);
        }
    }

    private void appendOrder(SideBook book, int levelSlot, int orderSlot) {
        int tail = book.levelTails[levelSlot];
        orderPrev[orderSlot] = tail;
        orderNext[orderSlot] = NO_INDEX;
        if (tail == NO_INDEX) {
            book.levelHeads[levelSlot] = orderSlot;
            book.levelTails[levelSlot] = orderSlot;
            return;
        }

        orderNext[tail] = orderSlot;
        book.levelTails[levelSlot] = orderSlot;
    }

    private void removeMatchedHead(SideBook book, int levelSlot, int orderSlot, int nextOrderSlot) {
        orderPrev[orderSlot] = NO_INDEX;
        orderNext[orderSlot] = NO_INDEX;
        if (nextOrderSlot == NO_INDEX) {
            book.levelHeads[levelSlot] = NO_INDEX;
            book.levelTails[levelSlot] = NO_INDEX;
            book.removeLevel(levelSlot);
            return;
        }

        book.levelHeads[levelSlot] = nextOrderSlot;
        orderPrev[nextOrderSlot] = NO_INDEX;
    }

    private int allocateOrderSlot() {
        int orderSlot = freeOrderSlot;
        if (orderSlot != NO_INDEX) {
            freeOrderSlot = orderNext[orderSlot];
            return orderSlot;
        }

        orderSlot = nextOrderSlot++;
        if (orderSlot == orderCapacity) {
            growOrderStorage();
        }
        return orderSlot;
    }

    private void releaseOrderSlot(int orderSlot) {
        orderMapSlots[orderSlot] = NO_INDEX;
        orderNext[orderSlot] = freeOrderSlot;
        freeOrderSlot = orderSlot;
    }

    private void growOrderStorage() {
        int newCapacity = orderCapacity << 1;
        orderIds = Arrays.copyOf(orderIds, newCapacity);
        orderQuantities = Arrays.copyOf(orderQuantities, newCapacity);
        orderLevels = growIntArray(orderLevels, newCapacity);
        orderPrev = growIntArray(orderPrev, newCapacity);
        orderNext = growIntArray(orderNext, newCapacity);
        orderMapSlots = growIntArray(orderMapSlots, newCapacity);
        orderSides = Arrays.copyOf(orderSides, newCapacity);
        orderCapacity = newCapacity;
    }

    private int[] growIntArray(int[] source, int newCapacity) {
        int oldLength = source.length;
        int[] copy = Arrays.copyOf(source, newCapacity);
        Arrays.fill(copy, oldLength, newCapacity, NO_INDEX);
        return copy;
    }

    private List<Order> snapshot(SideBook book) {
        List<Integer> levelSlots = book.snapshotLevelSlots();
        List<Order> orders = new ArrayList<>(orderById.size());
        Order.Side side = book.side();
        for (int levelSlot : levelSlots) {
            long price = book.levelPrices[levelSlot];
            for (int orderSlot = book.levelHeads[levelSlot]; orderSlot != NO_INDEX; orderSlot = orderNext[orderSlot]) {
                orders.add(new Order(orderIds[orderSlot], side, price, orderQuantities[orderSlot]));
            }
        }
        return Collections.unmodifiableList(orders);
    }

    private static int[] filledIntArray(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }

    private static final class SideBook {
        private static final int INITIAL_LEVEL_CAPACITY = 256;
        private static final int INITIAL_HEAP_CAPACITY = 256;
        private static final int HEAP_ARITY = 5;

        private final boolean buySide;
        private final byte sideFlag;
        private final LongIntMap levels = new LongIntMap(256, 0.5f, NO_INDEX);

        private long[] levelPrices = new long[INITIAL_LEVEL_CAPACITY];
        private long[] levelKeys = new long[INITIAL_LEVEL_CAPACITY];
        private int[] levelHeads = filledIntArray(INITIAL_LEVEL_CAPACITY, NO_INDEX);
        private int[] levelTails = filledIntArray(INITIAL_LEVEL_CAPACITY, NO_INDEX);
        private int[] levelHeapIndex = filledIntArray(INITIAL_LEVEL_CAPACITY, NO_INDEX);
        private int[] levelMapSlots = filledIntArray(INITIAL_LEVEL_CAPACITY, NO_INDEX);
        private int levelCapacity = INITIAL_LEVEL_CAPACITY;
        private int nextLevelSlot;
        private int freeLevelSlot = NO_INDEX;

        private int[] heap = new int[INITIAL_HEAP_CAPACITY];
        private int heapSize;

        private SideBook(boolean buySide) {
            this.buySide = buySide;
            this.sideFlag = buySide ? BUY_SIDE : 0;
        }

        private int level(long price) {
            return levels.get(price);
        }

        private int addLevel(long price) {
            int levelSlot = allocateLevelSlot();
            levelPrices[levelSlot] = price;
            levelKeys[levelSlot] = buySide ? -price : price;
            levelHeads[levelSlot] = NO_INDEX;
            levelTails[levelSlot] = NO_INDEX;
            levels.put(price, levelSlot, levelMapSlots);
            push(levelSlot);
            return levelSlot;
        }

        private int bestLevel() {
            return heapSize == 0 ? NO_INDEX : heap[0];
        }

        private void removeLevel(int levelSlot) {
            levels.removeValue(levelSlot, levelMapSlots);
            removeAt(levelHeapIndex[levelSlot]);
            releaseLevelSlot(levelSlot);
        }

        private void push(int levelSlot) {
            if (heapSize == heap.length) {
                heap = Arrays.copyOf(heap, heap.length << 1);
            }

            heap[heapSize] = levelSlot;
            levelHeapIndex[levelSlot] = heapSize;
            siftUp(heapSize++);
        }

        private void removeAt(int index) {
            int lastIndex = --heapSize;
            int removedLevel = heap[index];
            int replacement = heap[lastIndex];
            levelHeapIndex[removedLevel] = NO_INDEX;

            if (index == lastIndex) {
                return;
            }

            heap[index] = replacement;
            levelHeapIndex[replacement] = index;
            int parent = (index - 1) / HEAP_ARITY;
            if (index > 0 && levelKeys[replacement] < levelKeys[heap[parent]]) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }

        private void siftUp(int index) {
            int levelSlot = heap[index];
            long levelKey = levelKeys[levelSlot];
            while (index > 0) {
                int parent = (index - 1) / HEAP_ARITY;
                int parentSlot = heap[parent];
                if (levelKey >= levelKeys[parentSlot]) {
                    break;
                }

                heap[index] = parentSlot;
                levelHeapIndex[parentSlot] = index;
                index = parent;
            }
            heap[index] = levelSlot;
            levelHeapIndex[levelSlot] = index;
        }

        private void siftDown(int index) {
            int levelSlot = heap[index];
            long levelKey = levelKeys[levelSlot];
            while (true) {
                int firstChild = index * HEAP_ARITY + 1;
                if (firstChild >= heapSize) {
                    break;
                }

                int bestChild = firstChild;
                long bestChildKey = levelKeys[heap[firstChild]];
                int childLimit = Math.min(firstChild + HEAP_ARITY, heapSize);
                for (int child = firstChild + 1; child < childLimit; child++) {
                    int childSlot = heap[child];
                    long childKey = levelKeys[childSlot];
                    if (childKey < bestChildKey) {
                        bestChild = child;
                        bestChildKey = childKey;
                    }
                }

                if (bestChildKey >= levelKey) {
                    break;
                }

                int childSlot = heap[bestChild];
                heap[index] = childSlot;
                levelHeapIndex[childSlot] = index;
                index = bestChild;
            }

            heap[index] = levelSlot;
            levelHeapIndex[levelSlot] = index;
        }

        private int allocateLevelSlot() {
            int levelSlot = freeLevelSlot;
            if (levelSlot != NO_INDEX) {
                freeLevelSlot = levelHeads[levelSlot];
                return levelSlot;
            }

            levelSlot = nextLevelSlot++;
            if (levelSlot == levelCapacity) {
                growLevelStorage();
            }
            return levelSlot;
        }

        private void releaseLevelSlot(int levelSlot) {
            levelMapSlots[levelSlot] = NO_INDEX;
            levelHeads[levelSlot] = freeLevelSlot;
            freeLevelSlot = levelSlot;
        }

        private void growLevelStorage() {
            int newCapacity = levelCapacity << 1;
            levelPrices = Arrays.copyOf(levelPrices, newCapacity);
            levelKeys = Arrays.copyOf(levelKeys, newCapacity);
            levelHeads = growIntArray(levelHeads, newCapacity);
            levelTails = growIntArray(levelTails, newCapacity);
            levelHeapIndex = growIntArray(levelHeapIndex, newCapacity);
            levelMapSlots = growIntArray(levelMapSlots, newCapacity);
            levelCapacity = newCapacity;
        }

        private int[] growIntArray(int[] source, int newCapacity) {
            int oldLength = source.length;
            int[] copy = Arrays.copyOf(source, newCapacity);
            Arrays.fill(copy, oldLength, newCapacity, NO_INDEX);
            return copy;
        }

        private List<Integer> snapshotLevelSlots() {
            List<Integer> levelSlots = new ArrayList<>(heapSize);
            for (int i = 0; i < heapSize; i++) {
                levelSlots.add(heap[i]);
            }
            levelSlots.sort((left, right) -> buySide
                    ? Long.compare(levelPrices[right], levelPrices[left])
                    : Long.compare(levelPrices[left], levelPrices[right]));
            return levelSlots;
        }

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }
    }

    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private int size;
        private final float loadFactor;
        private final int missingValue;
        private int resizeThreshold;

        private LongIntMap(int capacity, float loadFactor, int missingValue) {
            int actualCapacity = 1;
            while (actualCapacity < capacity) {
                actualCapacity <<= 1;
            }
            this.loadFactor = loadFactor;
            this.missingValue = missingValue;
            keys = new long[actualCapacity];
            values = filledIntArray(actualCapacity, missingValue);
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
                if (value == missingValue) {
                    return missingValue;
                }
                if (keys[index] == key) {
                    return value;
                }
                index = (index + 1) & mask;
            }
        }

        private void put(long key, int value, int[] slotIndexes) {
            if (size >= resizeThreshold) {
                resize(slotIndexes);
            }

            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int current = values[index];
                if (current == missingValue) {
                    keys[index] = key;
                    values[index] = value;
                    slotIndexes[value] = index;
                    size++;
                    return;
                }
                if (keys[index] == key) {
                    if (current != missingValue) {
                        slotIndexes[current] = missingValue;
                    }
                    values[index] = value;
                    slotIndexes[value] = index;
                    return;
                }
                index = (index + 1) & mask;
            }
        }

        private int remove(long key, int[] slotIndexes) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int current = values[index];
                if (current == missingValue) {
                    return missingValue;
                }
                if (keys[index] == key) {
                    int removed = current;
                    slotIndexes[removed] = missingValue;
                    deleteIndex(index, slotIndexes);
                    return removed;
                }
                index = (index + 1) & mask;
            }
        }

        private void removeValue(int value, int[] slotIndexes) {
            int index = slotIndexes[value];
            if (index == missingValue) {
                return;
            }
            slotIndexes[value] = missingValue;
            deleteIndex(index, slotIndexes);
        }

        private void deleteIndex(int index, int[] slotIndexes) {
            int mask = values.length - 1;
            size--;
            int gap = index;
            int next = (index + 1) & mask;
            while (true) {
                int value = values[next];
                if (value == missingValue) {
                    values[gap] = missingValue;
                    return;
                }

                int home = mix(keys[next]) & mask;
                if (((next - home) & mask) >= ((gap - home) & mask)) {
                    keys[gap] = keys[next];
                    values[gap] = value;
                    slotIndexes[value] = gap;
                    gap = next;
                }
                next = (next + 1) & mask;
            }
        }

        private void resize(int[] slotIndexes) {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = filledIntArray(oldValues.length << 1, missingValue);
            resizeThreshold = (int) (values.length * loadFactor);

            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                int value = oldValues[i];
                if (value != missingValue) {
                    reinsert(oldKeys[i], value, slotIndexes);
                }
            }
            size = oldSize;
        }

        private void reinsert(long key, int value, int[] slotIndexes) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (values[index] != missingValue) {
                index = (index + 1) & mask;
            }
            keys[index] = key;
            values[index] = value;
            slotIndexes[value] = index;
            size++;
        }

        private int mix(long key) {
            return (int) (key ^ (key >>> 32));
        }
    }
}
