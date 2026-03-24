package com.xiaohanc.orderbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class OrderBookImpl implements OrderBook {
    private static final int NO_INDEX = -1;
    private static final int ASK_LEVEL_FLAG = Integer.MIN_VALUE;
    private static final int PAGE_SHIFT = 6;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    private final SideBook bids = new SideBook(true);
    private final SideBook asks = new SideBook(false);
    private final OrderMap orderById = new OrderMap(16384, 0.55f);
    private final OrderMatchListener listener;

    private long[] orderIds = new long[1024];
    private long[] orderQuantities = new long[1024];
    private int[] orderLevels = filledIntArray(1024, NO_INDEX);
    private int[] orderPrev = filledIntArray(1024, NO_INDEX);
    private int[] orderNext = filledIntArray(1024, NO_INDEX);
    private int[] orderMapSlots = filledIntArray(1024, NO_INDEX);
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
        int levelRef = book.levelRef(price);
        if (levelRef == NO_INDEX) {
            levelRef = book.createLevel(price);
        }

        int orderSlot = allocateOrderSlot();
        orderIds[orderSlot] = id;
        orderQuantities[orderSlot] = remainingQuantity;
        orderLevels[orderSlot] = side == Order.Side.BUY ? levelRef : levelRef | ASK_LEVEL_FLAG;
        book.appendOrder(levelRef, orderSlot, orderPrev, orderNext);
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

        Order.Side side = orderLevels[orderSlot] >= 0 ? Order.Side.BUY : Order.Side.SELL;
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
            int levelRef = oppositeBook.bestLevelRef();
            if (levelRef == NO_INDEX) {
                break;
            }

            long matchedPrice = oppositeBook.priceOf(levelRef);
            if (!crosses(incomingSide, incomingPrice, matchedPrice)) {
                break;
            }

            int makerSlot = oppositeBook.levelHead(levelRef);
            while (makerSlot != NO_INDEX && remainingQuantity > 0) {
                int nextMakerSlot = orderNext[makerSlot];
                long matchedQuantity = Math.min(remainingQuantity, orderQuantities[makerSlot]);
                listener.onMatch(orderIds[makerSlot], incomingId, matchedPrice, matchedQuantity);

                remainingQuantity -= matchedQuantity;
                long remainingMakerQuantity = orderQuantities[makerSlot] - matchedQuantity;
                if (remainingMakerQuantity == 0) {
                    orderById.removeValue(makerSlot, orderMapSlots);
                    oppositeBook.removeMatchedHead(levelRef, makerSlot, nextMakerSlot, orderPrev, orderNext);
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
        int encodedLevelRef = orderLevels[orderSlot];
        SideBook book = encodedLevelRef >= 0 ? bids : asks;
        int levelRef = encodedLevelRef & Integer.MAX_VALUE;
        int prevOrderSlot = orderPrev[orderSlot];
        int nextOrderSlot = orderNext[orderSlot];

        book.unlinkOrder(levelRef, orderSlot, prevOrderSlot, nextOrderSlot, orderPrev, orderNext);
        orderPrev[orderSlot] = NO_INDEX;
        orderNext[orderSlot] = NO_INDEX;
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
        orderCapacity = newCapacity;
    }

    private int[] growIntArray(int[] source, int newCapacity) {
        int oldLength = source.length;
        int[] copy = Arrays.copyOf(source, newCapacity);
        Arrays.fill(copy, oldLength, newCapacity, NO_INDEX);
        return copy;
    }

    private List<Order> snapshot(SideBook book) {
        List<Integer> levelRefs = book.snapshotLevelRefs();
        List<Order> orders = new ArrayList<>(orderById.size());
        Order.Side side = book.side();
        for (int levelRef : levelRefs) {
            long price = book.priceOf(levelRef);
            for (int orderSlot = book.levelHead(levelRef); orderSlot != NO_INDEX; orderSlot = orderNext[orderSlot]) {
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

    private static final class OrderMap {
        private long[] keys;
        private int[] values;
        private int size;
        private final float loadFactor;
        private int resizeThreshold;

        private OrderMap(int capacity, float loadFactor) {
            int actualCapacity = 1;
            while (actualCapacity < capacity) {
                actualCapacity <<= 1;
            }
            this.loadFactor = loadFactor;
            keys = new long[actualCapacity];
            values = filledIntArray(actualCapacity, NO_INDEX);
            resizeThreshold = (int) (actualCapacity * loadFactor);
        }

        private int size() {
            return size;
        }

        private void put(long key, int value, int[] slotIndexes) {
            if (size >= resizeThreshold) {
                resize(slotIndexes);
            }

            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (true) {
                int current = values[index];
                if (current == NO_INDEX) {
                    keys[index] = key;
                    values[index] = value;
                    slotIndexes[value] = index;
                    size++;
                    return;
                }
                if (keys[index] == key) {
                    slotIndexes[current] = NO_INDEX;
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
                if (current == NO_INDEX) {
                    return NO_INDEX;
                }
                if (keys[index] == key) {
                    slotIndexes[current] = NO_INDEX;
                    deleteIndex(index, slotIndexes);
                    return current;
                }
                index = (index + 1) & mask;
            }
        }

        private void removeValue(int value, int[] slotIndexes) {
            int index = slotIndexes[value];
            if (index == NO_INDEX) {
                return;
            }
            slotIndexes[value] = NO_INDEX;
            deleteIndex(index, slotIndexes);
        }

        private void deleteIndex(int index, int[] slotIndexes) {
            int mask = values.length - 1;
            size--;
            int gap = index;
            int next = (index + 1) & mask;
            while (true) {
                int value = values[next];
                if (value == NO_INDEX) {
                    values[gap] = NO_INDEX;
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
            values = filledIntArray(oldValues.length << 1, NO_INDEX);
            resizeThreshold = (int) (values.length * loadFactor);

            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                int value = oldValues[i];
                if (value != NO_INDEX) {
                    reinsert(oldKeys[i], value, slotIndexes);
                }
            }
            size = oldSize;
        }

        private void reinsert(long key, int value, int[] slotIndexes) {
            int mask = values.length - 1;
            int index = mix(key) & mask;
            while (values[index] != NO_INDEX) {
                index = (index + 1) & mask;
            }
            keys[index] = key;
            values[index] = value;
            slotIndexes[value] = index;
            size++;
        }

        private int mix(long key) {
            long mixed = key ^ (key >>> 33);
            mixed ^= mixed >>> 17;
            return (int) mixed;
        }
    }

    private static final class SideBook {
        private static final int INITIAL_PAGE_CAPACITY = 256;
        private static final int INITIAL_HEAP_CAPACITY = 256;
        private static final int HEAP_ARITY = 5;

        private final boolean buySide;
        private final PageMap pages = new PageMap(256, 0.5f);

        private long[] pageKeys = new long[INITIAL_PAGE_CAPACITY];
        private long[] pageActiveMasks = new long[INITIAL_PAGE_CAPACITY];
        private int[] pageHeapIndex = filledIntArray(INITIAL_PAGE_CAPACITY, NO_INDEX);
        private int[] pageMapSlots = filledIntArray(INITIAL_PAGE_CAPACITY, NO_INDEX);
        private int[] pageNextFree = filledIntArray(INITIAL_PAGE_CAPACITY, NO_INDEX);
        private int pageCapacity = INITIAL_PAGE_CAPACITY;
        private int nextPageSlot;
        private int freePageSlot = NO_INDEX;

        private int[] levelHeads = filledIntArray(INITIAL_PAGE_CAPACITY << PAGE_SHIFT, NO_INDEX);
        private int[] levelTails = filledIntArray(INITIAL_PAGE_CAPACITY << PAGE_SHIFT, NO_INDEX);

        private int[] heap = new int[INITIAL_HEAP_CAPACITY];
        private int heapSize;

        private SideBook(boolean buySide) {
            this.buySide = buySide;
        }

        private int levelRef(long price) {
            long pageKey = pageKey(price);
            int pageSlot = pages.get(pageKey);
            if (pageSlot == NO_INDEX) {
                return NO_INDEX;
            }

            int offset = pageOffset(price);
            long bit = 1L << offset;
            return (pageActiveMasks[pageSlot] & bit) == 0 ? NO_INDEX : levelRef(pageSlot, offset);
        }

        private int createLevel(long price) {
            long pageKey = pageKey(price);
            int pageSlot = pages.get(pageKey);
            if (pageSlot == NO_INDEX) {
                pageSlot = allocatePageSlot();
                resetPage(pageSlot);
                pageKeys[pageSlot] = pageKey;
                pages.put(pageKey, pageSlot, pageMapSlots);
            }

            int offset = pageOffset(price);
            long activeMask = pageActiveMasks[pageSlot];
            long bit = 1L << offset;
            if ((activeMask & bit) == 0) {
                pageActiveMasks[pageSlot] = activeMask | bit;
                if (activeMask == 0L) {
                    push(pageSlot);
                }
            }
            return levelRef(pageSlot, offset);
        }

        private int bestLevelRef() {
            if (heapSize == 0) {
                return NO_INDEX;
            }

            int pageSlot = heap[0];
            long activeMask = pageActiveMasks[pageSlot];
            int offset = buySide
                    ? Long.SIZE - 1 - Long.numberOfLeadingZeros(activeMask)
                    : Long.numberOfTrailingZeros(activeMask);
            return levelRef(pageSlot, offset);
        }

        private long priceOf(int levelRef) {
            int pageSlot = pageSlot(levelRef);
            return (pageKeys[pageSlot] << PAGE_SHIFT) | levelOffset(levelRef);
        }

        private int levelHead(int levelRef) {
            return levelHeads[levelRef];
        }

        private void appendOrder(int levelRef, int orderSlot, int[] orderPrev, int[] orderNext) {
            int tail = levelTails[levelRef];
            orderPrev[orderSlot] = tail;
            orderNext[orderSlot] = NO_INDEX;
            if (tail == NO_INDEX) {
                levelHeads[levelRef] = orderSlot;
                levelTails[levelRef] = orderSlot;
                return;
            }

            orderNext[tail] = orderSlot;
            levelTails[levelRef] = orderSlot;
        }

        private void unlinkOrder(
                int levelRef,
                int orderSlot,
                int prevOrderSlot,
                int nextOrderSlot,
                int[] orderPrev,
                int[] orderNext
        ) {
            if (prevOrderSlot == NO_INDEX) {
                levelHeads[levelRef] = nextOrderSlot;
            } else {
                orderNext[prevOrderSlot] = nextOrderSlot;
            }

            if (nextOrderSlot == NO_INDEX) {
                levelTails[levelRef] = prevOrderSlot;
            } else {
                orderPrev[nextOrderSlot] = prevOrderSlot;
            }

            if (levelHeads[levelRef] == NO_INDEX) {
                deactivateLevel(levelRef);
            }
        }

        private void removeMatchedHead(int levelRef, int orderSlot, int nextOrderSlot, int[] orderPrev, int[] orderNext) {
            orderPrev[orderSlot] = NO_INDEX;
            orderNext[orderSlot] = NO_INDEX;
            if (nextOrderSlot == NO_INDEX) {
                levelHeads[levelRef] = NO_INDEX;
                levelTails[levelRef] = NO_INDEX;
                deactivateLevel(levelRef);
                return;
            }

            levelHeads[levelRef] = nextOrderSlot;
            orderPrev[nextOrderSlot] = NO_INDEX;
        }

        private void deactivateLevel(int levelRef) {
            int pageSlot = pageSlot(levelRef);
            long activeMask = pageActiveMasks[pageSlot] & ~(1L << levelOffset(levelRef));
            pageActiveMasks[pageSlot] = activeMask;
            if (activeMask == 0L) {
                pages.removeValue(pageSlot, pageMapSlots);
                removeAt(pageHeapIndex[pageSlot]);
                releasePageSlot(pageSlot);
            }
        }

        private List<Integer> snapshotLevelRefs() {
            List<Integer> pageSlots = new ArrayList<>(heapSize);
            for (int i = 0; i < heapSize; i++) {
                pageSlots.add(heap[i]);
            }
            pageSlots.sort((left, right) -> buySide
                    ? Long.compare(pageKeys[right], pageKeys[left])
                    : Long.compare(pageKeys[left], pageKeys[right]));

            List<Integer> levelRefs = new ArrayList<>();
            for (int pageSlot : pageSlots) {
                long activeMask = pageActiveMasks[pageSlot];
                while (activeMask != 0L) {
                    int offset = buySide
                            ? Long.SIZE - 1 - Long.numberOfLeadingZeros(activeMask)
                            : Long.numberOfTrailingZeros(activeMask);
                    levelRefs.add(levelRef(pageSlot, offset));
                    activeMask &= ~(1L << offset);
                }
            }
            return levelRefs;
        }

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }

        private int allocatePageSlot() {
            int pageSlot = freePageSlot;
            if (pageSlot != NO_INDEX) {
                freePageSlot = pageNextFree[pageSlot];
                pageNextFree[pageSlot] = NO_INDEX;
                return pageSlot;
            }

            pageSlot = nextPageSlot++;
            if (pageSlot == pageCapacity) {
                growPageStorage();
            }
            return pageSlot;
        }

        private void releasePageSlot(int pageSlot) {
            pageHeapIndex[pageSlot] = NO_INDEX;
            pageMapSlots[pageSlot] = NO_INDEX;
            pageActiveMasks[pageSlot] = 0L;
            pageNextFree[pageSlot] = freePageSlot;
            freePageSlot = pageSlot;
        }

        private void resetPage(int pageSlot) {
            int from = pageSlot << PAGE_SHIFT;
            int to = from + PAGE_SIZE;
            Arrays.fill(levelHeads, from, to, NO_INDEX);
            Arrays.fill(levelTails, from, to, NO_INDEX);
            pageHeapIndex[pageSlot] = NO_INDEX;
            pageMapSlots[pageSlot] = NO_INDEX;
            pageNextFree[pageSlot] = NO_INDEX;
            pageActiveMasks[pageSlot] = 0L;
        }

        private void growPageStorage() {
            int newCapacity = pageCapacity << 1;
            pageKeys = Arrays.copyOf(pageKeys, newCapacity);
            pageActiveMasks = Arrays.copyOf(pageActiveMasks, newCapacity);
            pageHeapIndex = growIntArray(pageHeapIndex, newCapacity);
            pageMapSlots = growIntArray(pageMapSlots, newCapacity);
            pageNextFree = growIntArray(pageNextFree, newCapacity);
            levelHeads = growIntArray(levelHeads, newCapacity << PAGE_SHIFT);
            levelTails = growIntArray(levelTails, newCapacity << PAGE_SHIFT);
            pageCapacity = newCapacity;
        }

        private int[] growIntArray(int[] source, int newCapacity) {
            int oldLength = source.length;
            int[] copy = Arrays.copyOf(source, newCapacity);
            Arrays.fill(copy, oldLength, newCapacity, NO_INDEX);
            return copy;
        }

        private void push(int pageSlot) {
            if (heapSize == heap.length) {
                heap = Arrays.copyOf(heap, heap.length << 1);
            }

            heap[heapSize] = pageSlot;
            pageHeapIndex[pageSlot] = heapSize;
            siftUp(heapSize++);
        }

        private void removeAt(int index) {
            int lastIndex = --heapSize;
            int removedPage = heap[index];
            int replacement = heap[lastIndex];
            pageHeapIndex[removedPage] = NO_INDEX;

            if (index == lastIndex) {
                return;
            }

            heap[index] = replacement;
            pageHeapIndex[replacement] = index;
            int parent = (index - 1) / HEAP_ARITY;
            if (index > 0 && betterPage(replacement, heap[parent])) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }

        private void siftUp(int index) {
            int pageSlot = heap[index];
            while (index > 0) {
                int parent = (index - 1) / HEAP_ARITY;
                int parentSlot = heap[parent];
                if (!betterPage(pageSlot, parentSlot)) {
                    break;
                }

                heap[index] = parentSlot;
                pageHeapIndex[parentSlot] = index;
                index = parent;
            }
            heap[index] = pageSlot;
            pageHeapIndex[pageSlot] = index;
        }

        private void siftDown(int index) {
            int pageSlot = heap[index];
            while (true) {
                int firstChild = index * HEAP_ARITY + 1;
                if (firstChild >= heapSize) {
                    break;
                }

                int bestChild = firstChild;
                int childLimit = Math.min(firstChild + HEAP_ARITY, heapSize);
                for (int child = firstChild + 1; child < childLimit; child++) {
                    if (betterPage(heap[child], heap[bestChild])) {
                        bestChild = child;
                    }
                }

                int childSlot = heap[bestChild];
                if (!betterPage(childSlot, pageSlot)) {
                    break;
                }

                heap[index] = childSlot;
                pageHeapIndex[childSlot] = index;
                index = bestChild;
            }

            heap[index] = pageSlot;
            pageHeapIndex[pageSlot] = index;
        }

        private boolean betterPage(int leftPageSlot, int rightPageSlot) {
            return buySide
                    ? pageKeys[leftPageSlot] > pageKeys[rightPageSlot]
                    : pageKeys[leftPageSlot] < pageKeys[rightPageSlot];
        }

        private long pageKey(long price) {
            return price >> PAGE_SHIFT;
        }

        private int pageOffset(long price) {
            return (int) (price & PAGE_MASK);
        }

        private int pageSlot(int levelRef) {
            return levelRef >>> PAGE_SHIFT;
        }

        private int levelOffset(int levelRef) {
            return levelRef & PAGE_MASK;
        }

        private int levelRef(int pageSlot, int offset) {
            return (pageSlot << PAGE_SHIFT) | offset;
        }

        private final class PageMap {
            private long[] keys;
            private int[] values;
            private int size;
            private final float loadFactor;
            private int resizeThreshold;

            private PageMap(int capacity, float loadFactor) {
                int actualCapacity = 1;
                while (actualCapacity < capacity) {
                    actualCapacity <<= 1;
                }
                this.loadFactor = loadFactor;
                keys = new long[actualCapacity];
                values = filledIntArray(actualCapacity, NO_INDEX);
                resizeThreshold = (int) (actualCapacity * loadFactor);
            }

            private int get(long key) {
                int mask = values.length - 1;
                int index = mix(key) & mask;
                while (true) {
                    int value = values[index];
                    if (value == NO_INDEX) {
                        return NO_INDEX;
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
                    if (current == NO_INDEX) {
                        keys[index] = key;
                        values[index] = value;
                        slotIndexes[value] = index;
                        size++;
                        return;
                    }
                    if (keys[index] == key) {
                        slotIndexes[current] = NO_INDEX;
                        values[index] = value;
                        slotIndexes[value] = index;
                        return;
                    }
                    index = (index + 1) & mask;
                }
            }

            private void removeValue(int value, int[] slotIndexes) {
                int index = slotIndexes[value];
                if (index == NO_INDEX) {
                    return;
                }
                slotIndexes[value] = NO_INDEX;
                deleteIndex(index, slotIndexes);
            }

            private void deleteIndex(int index, int[] slotIndexes) {
                int mask = values.length - 1;
                size--;
                int gap = index;
                int next = (index + 1) & mask;
                while (true) {
                    int value = values[next];
                    if (value == NO_INDEX) {
                        values[gap] = NO_INDEX;
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
                values = filledIntArray(oldValues.length << 1, NO_INDEX);
                resizeThreshold = (int) (values.length * loadFactor);

                int oldSize = size;
                size = 0;
                for (int i = 0; i < oldValues.length; i++) {
                    int value = oldValues[i];
                    if (value != NO_INDEX) {
                        reinsert(oldKeys[i], value, slotIndexes);
                    }
                }
                size = oldSize;
            }

            private void reinsert(long key, int value, int[] slotIndexes) {
                int mask = values.length - 1;
                int index = mix(key) & mask;
                while (values[index] != NO_INDEX) {
                    index = (index + 1) & mask;
                }
                keys[index] = key;
                values[index] = value;
                slotIndexes[value] = index;
                size++;
            }

            private int mix(long key) {
                long mixed = key ^ (key >>> 33);
                mixed ^= mixed >>> 17;
                return (int) mixed;
            }
        }
    }
}
