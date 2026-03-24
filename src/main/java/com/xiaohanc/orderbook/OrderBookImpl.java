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
        int levelSlot = book.level(price);
        if (levelSlot == NO_INDEX) {
            levelSlot = book.addLevel(price);
        }

        int orderSlot = allocateOrderSlot();
        orderIds[orderSlot] = id;
        orderQuantities[orderSlot] = remainingQuantity;
        orderLevels[orderSlot] = side == Order.Side.BUY ? levelSlot : levelSlot | ASK_LEVEL_FLAG;
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
            int levelSlot = oppositeBook.bestLevel();
            if (levelSlot == NO_INDEX) {
                break;
            }

            long matchedPrice = oppositeBook.priceOf(levelSlot);
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
        int levelRef = orderLevels[orderSlot];
        SideBook book = levelRef >= 0 ? bids : asks;
        int levelSlot = levelRef & Integer.MAX_VALUE;
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
            long price = book.priceOf(levelSlot);
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
        private static final int INITIAL_PAGE_SLOT_CAPACITY = 256;
        private static final int INITIAL_DIRECTORY_CAPACITY = 4096;

        private final boolean buySide;

        private long basePageKey;
        private boolean directoryInitialized;
        private int[] directorySlots = filledIntArray(INITIAL_DIRECTORY_CAPACITY, NO_INDEX);
        private long[] nonEmptyPageWords = new long[(INITIAL_DIRECTORY_CAPACITY + Long.SIZE - 1) / Long.SIZE];
        private long[] nonEmptyPageWordSummary = new long[(nonEmptyPageWords.length + Long.SIZE - 1) / Long.SIZE];

        private long[] pageKeys = new long[INITIAL_PAGE_SLOT_CAPACITY];
        private long[] pageMasks = new long[INITIAL_PAGE_SLOT_CAPACITY];
        private int[] pageDirectoryIndexes = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] pageNextFree = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int pageSlotCapacity = INITIAL_PAGE_SLOT_CAPACITY;
        private int nextPageSlot;
        private int freePageSlot = NO_INDEX;

        private int[] levelHeads = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY << PAGE_SHIFT, NO_INDEX);
        private int[] levelTails = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY << PAGE_SHIFT, NO_INDEX);

        private SideBook(boolean buySide) {
            this.buySide = buySide;
        }

        private int level(long price) {
            if (!directoryInitialized) {
                return NO_INDEX;
            }

            long pageKey = price >> PAGE_SHIFT;
            int directoryIndex = directoryIndex(pageKey);
            if (directoryIndex == NO_INDEX) {
                return NO_INDEX;
            }

            int pageSlot = directorySlots[directoryIndex];
            if (pageSlot == NO_INDEX) {
                return NO_INDEX;
            }

            int offset = (int) (price & PAGE_MASK);
            return (pageMasks[pageSlot] & (1L << offset)) == 0 ? NO_INDEX : levelRef(pageSlot, offset);
        }

        private int addLevel(long price) {
            long pageKey = price >> PAGE_SHIFT;
            ensureDirectoryContains(pageKey);
            int directoryIndex = directoryIndex(pageKey);
            int pageSlot = directorySlots[directoryIndex];
            if (pageSlot == NO_INDEX) {
                pageSlot = allocatePageSlot();
                pageKeys[pageSlot] = pageKey;
                pageMasks[pageSlot] = 0L;
                pageDirectoryIndexes[pageSlot] = directoryIndex;
                directorySlots[directoryIndex] = pageSlot;
            }

            int offset = (int) (price & PAGE_MASK);
            long mask = pageMasks[pageSlot];
            long bit = 1L << offset;
            if ((mask & bit) == 0) {
                pageMasks[pageSlot] = mask | bit;
                if (mask == 0L) {
                    markPageActive(directoryIndex);
                }
            }
            return levelRef(pageSlot, offset);
        }

        private int bestLevel() {
            int directoryIndex = bestPageIndex();
            if (directoryIndex == NO_INDEX) {
                return NO_INDEX;
            }

            int pageSlot = directorySlots[directoryIndex];
            long mask = pageMasks[pageSlot];
            int offset = buySide
                    ? Long.SIZE - 1 - Long.numberOfLeadingZeros(mask)
                    : Long.numberOfTrailingZeros(mask);
            return levelRef(pageSlot, offset);
        }

        private long priceOf(int levelSlot) {
            int pageSlot = pageSlot(levelSlot);
            return (pageKeys[pageSlot] << PAGE_SHIFT) | levelOffset(levelSlot);
        }

        private void removeLevel(int levelSlot) {
            int pageSlot = pageSlot(levelSlot);
            long remainingMask = pageMasks[pageSlot] & ~(1L << levelOffset(levelSlot));
            pageMasks[pageSlot] = remainingMask;
            if (remainingMask == 0L) {
                int directoryIndex = pageDirectoryIndexes[pageSlot];
                directorySlots[directoryIndex] = NO_INDEX;
                clearPageActive(directoryIndex);
                releasePageSlot(pageSlot);
            }
        }

        private List<Integer> snapshotLevelSlots() {
            List<Integer> levelSlots = new ArrayList<>();
            if (!directoryInitialized) {
                return levelSlots;
            }

            if (buySide) {
                for (int directoryIndex = directorySlots.length - 1; directoryIndex >= 0; directoryIndex--) {
                    int pageSlot = directorySlots[directoryIndex];
                    if (pageSlot == NO_INDEX) {
                        continue;
                    }

                    long mask = pageMasks[pageSlot];
                    while (mask != 0L) {
                        int offset = Long.SIZE - 1 - Long.numberOfLeadingZeros(mask);
                        levelSlots.add(levelRef(pageSlot, offset));
                        mask &= ~(1L << offset);
                    }
                }
            } else {
                for (int directoryIndex = 0; directoryIndex < directorySlots.length; directoryIndex++) {
                    int pageSlot = directorySlots[directoryIndex];
                    if (pageSlot == NO_INDEX) {
                        continue;
                    }

                    long mask = pageMasks[pageSlot];
                    while (mask != 0L) {
                        int offset = Long.numberOfTrailingZeros(mask);
                        levelSlots.add(levelRef(pageSlot, offset));
                        mask &= mask - 1;
                    }
                }
            }
            return levelSlots;
        }

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }

        private void ensureDirectoryContains(long pageKey) {
            if (!directoryInitialized) {
                basePageKey = pageKey - (directorySlots.length >>> 1);
                directoryInitialized = true;
                return;
            }

            if (directoryIndex(pageKey) != NO_INDEX) {
                return;
            }

            growDirectory(pageKey);
        }

        private int directoryIndex(long pageKey) {
            long index = pageKey - basePageKey;
            return index >= 0 && index < directorySlots.length ? (int) index : NO_INDEX;
        }

        private void growDirectory(long requiredPageKey) {
            int oldCapacity = directorySlots.length;
            int newCapacity = oldCapacity;
            long newBase = basePageKey;
            do {
                newCapacity <<= 1;
                newBase = basePageKey - ((long) (newCapacity - oldCapacity) >>> 1);
            } while (requiredPageKey < newBase || requiredPageKey >= newBase + newCapacity);

            int[] oldDirectorySlots = directorySlots;
            directorySlots = filledIntArray(newCapacity, NO_INDEX);
            int shift = (int) (basePageKey - newBase);
            System.arraycopy(oldDirectorySlots, 0, directorySlots, shift, oldCapacity);
            basePageKey = newBase;

            nonEmptyPageWords = new long[(newCapacity + Long.SIZE - 1) / Long.SIZE];
            nonEmptyPageWordSummary = new long[(nonEmptyPageWords.length + Long.SIZE - 1) / Long.SIZE];
            rebuildDirectoryState();
        }

        private void rebuildDirectoryState() {
            Arrays.fill(nonEmptyPageWords, 0L);
            Arrays.fill(nonEmptyPageWordSummary, 0L);
            Arrays.fill(pageDirectoryIndexes, NO_INDEX);
            for (int directoryIndex = 0; directoryIndex < directorySlots.length; directoryIndex++) {
                int pageSlot = directorySlots[directoryIndex];
                if (pageSlot == NO_INDEX) {
                    continue;
                }

                pageDirectoryIndexes[pageSlot] = directoryIndex;
                if (pageMasks[pageSlot] != 0L) {
                    markPageActive(directoryIndex);
                }
            }
        }

        private void markPageActive(int directoryIndex) {
            int pageWordIndex = directoryIndex >>> 6;
            long pageBit = 1L << (directoryIndex & PAGE_MASK);
            long oldPageWord = nonEmptyPageWords[pageWordIndex];
            nonEmptyPageWords[pageWordIndex] = oldPageWord | pageBit;
            if (oldPageWord == 0L) {
                int summaryIndex = pageWordIndex >>> 6;
                nonEmptyPageWordSummary[summaryIndex] |= 1L << (pageWordIndex & PAGE_MASK);
            }
        }

        private void clearPageActive(int directoryIndex) {
            int pageWordIndex = directoryIndex >>> 6;
            long pageBit = 1L << (directoryIndex & PAGE_MASK);
            long newPageWord = nonEmptyPageWords[pageWordIndex] & ~pageBit;
            nonEmptyPageWords[pageWordIndex] = newPageWord;
            if (newPageWord == 0L) {
                int summaryIndex = pageWordIndex >>> 6;
                nonEmptyPageWordSummary[summaryIndex] &= ~(1L << (pageWordIndex & PAGE_MASK));
            }
        }

        private int bestPageIndex() {
            if (buySide) {
                for (int summaryIndex = nonEmptyPageWordSummary.length - 1; summaryIndex >= 0; summaryIndex--) {
                    long summaryWord = nonEmptyPageWordSummary[summaryIndex];
                    if (summaryWord == 0L) {
                        continue;
                    }

                    int pageWordIndex = (summaryIndex << 6) | (Long.SIZE - 1 - Long.numberOfLeadingZeros(summaryWord));
                    long pageWord = nonEmptyPageWords[pageWordIndex];
                    return (pageWordIndex << 6) | (Long.SIZE - 1 - Long.numberOfLeadingZeros(pageWord));
                }
                return NO_INDEX;
            }

            for (int summaryIndex = 0; summaryIndex < nonEmptyPageWordSummary.length; summaryIndex++) {
                long summaryWord = nonEmptyPageWordSummary[summaryIndex];
                if (summaryWord == 0L) {
                    continue;
                }

                int pageWordIndex = (summaryIndex << 6) | Long.numberOfTrailingZeros(summaryWord);
                long pageWord = nonEmptyPageWords[pageWordIndex];
                return (pageWordIndex << 6) | Long.numberOfTrailingZeros(pageWord);
            }
            return NO_INDEX;
        }

        private int allocatePageSlot() {
            int pageSlot = freePageSlot;
            if (pageSlot != NO_INDEX) {
                freePageSlot = pageNextFree[pageSlot];
                pageNextFree[pageSlot] = NO_INDEX;
                return pageSlot;
            }

            pageSlot = nextPageSlot++;
            if (pageSlot == pageSlotCapacity) {
                growPageStorage();
            }
            return pageSlot;
        }

        private void releasePageSlot(int pageSlot) {
            pageMasks[pageSlot] = 0L;
            pageDirectoryIndexes[pageSlot] = NO_INDEX;
            pageNextFree[pageSlot] = freePageSlot;
            freePageSlot = pageSlot;
        }

        private void growPageStorage() {
            int newCapacity = pageSlotCapacity << 1;
            pageKeys = Arrays.copyOf(pageKeys, newCapacity);
            pageMasks = Arrays.copyOf(pageMasks, newCapacity);
            pageDirectoryIndexes = growIntArray(pageDirectoryIndexes, newCapacity);
            pageNextFree = growIntArray(pageNextFree, newCapacity);
            levelHeads = growIntArray(levelHeads, newCapacity << PAGE_SHIFT);
            levelTails = growIntArray(levelTails, newCapacity << PAGE_SHIFT);
            pageSlotCapacity = newCapacity;
        }

        private int[] growIntArray(int[] source, int newCapacity) {
            int oldLength = source.length;
            int[] copy = Arrays.copyOf(source, newCapacity);
            Arrays.fill(copy, oldLength, newCapacity, NO_INDEX);
            return copy;
        }

        private int pageSlot(int levelSlot) {
            return levelSlot >>> PAGE_SHIFT;
        }

        private int levelOffset(int levelSlot) {
            return levelSlot & PAGE_MASK;
        }

        private int levelRef(int pageSlot, int levelOffset) {
            return (pageSlot << PAGE_SHIFT) | levelOffset;
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
            long mixed = key ^ (key >>> 33);
            mixed ^= mixed >>> 17;
            return (int) mixed;
        }
    }
}
