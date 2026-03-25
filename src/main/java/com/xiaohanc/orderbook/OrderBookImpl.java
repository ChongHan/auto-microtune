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
    private static final int PAGE_MASK = (1 << PAGE_SHIFT) - 1;

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

        clearOrderLinks(orderSlot);
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
        clearOrderLinks(orderSlot);
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

    private void clearOrderLinks(int orderSlot) {
        orderPrev[orderSlot] = NO_INDEX;
        orderNext[orderSlot] = NO_INDEX;
    }

    private static int[] growIntArray(int[] source, int newCapacity) {
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
        private static final int CORE_DIRECTORY_CAPACITY = 16384;

        private final boolean buySide;

        private long coreBasePageKey;
        private boolean coreInitialized;
        private int[] directorySlots = filledIntArray(CORE_DIRECTORY_CAPACITY, NO_INDEX);
        private long[] nonEmptyPageWords = new long[(CORE_DIRECTORY_CAPACITY + Long.SIZE - 1) / Long.SIZE];
        private long[] nonEmptyPageWordSummary = new long[(nonEmptyPageWords.length + Long.SIZE - 1) / Long.SIZE];

        private int overflowRoot = NO_INDEX;
        private int overflowBestPageSlot = NO_INDEX;

        private long[] pageKeys = new long[INITIAL_PAGE_SLOT_CAPACITY];
        private long[] pageMasks = new long[INITIAL_PAGE_SLOT_CAPACITY];
        private int[] pageDirectoryIndexes = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] overflowLeft = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] overflowRight = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] overflowParent = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] overflowPriorities = new int[INITIAL_PAGE_SLOT_CAPACITY];
        private int[] pageNextFree = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY, NO_INDEX);
        private int[] traversalStack = new int[INITIAL_PAGE_SLOT_CAPACITY];
        private int pageSlotCapacity = INITIAL_PAGE_SLOT_CAPACITY;
        private int nextPageSlot;
        private int freePageSlot = NO_INDEX;

        // The hot region stays in a fixed dense directory; far-out tails spill into the overflow tree.
        private int[] levelHeads = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY << PAGE_SHIFT, NO_INDEX);
        private int[] levelTails = filledIntArray(INITIAL_PAGE_SLOT_CAPACITY << PAGE_SHIFT, NO_INDEX);

        private SideBook(boolean buySide) {
            this.buySide = buySide;
        }

        private int level(long price) {
            long pageKey = price >> PAGE_SHIFT;
            int directoryIndex = directoryIndex(pageKey);
            int pageSlot = directoryIndex == NO_INDEX
                    ? findOverflowPage(pageKey)
                    : directorySlots[directoryIndex];
            if (pageSlot == NO_INDEX) {
                return NO_INDEX;
            }

            int offset = (int) (price & PAGE_MASK);
            return (pageMasks[pageSlot] & (1L << offset)) == 0 ? NO_INDEX : levelRef(pageSlot, offset);
        }

        private int addLevel(long price) {
            long pageKey = price >> PAGE_SHIFT;
            ensureCoreInitialized(pageKey);
            int directoryIndex = directoryIndex(pageKey);
            if (directoryIndex == NO_INDEX) {
                if (isBetterThanCore(pageKey)) {
                    rebaseCore(pageKey);
                    directoryIndex = directoryIndex(pageKey);
                } else {
                    return addOverflowLevel(pageKey, price);
                }
            }

            int pageSlot = directorySlots[directoryIndex];
            if (pageSlot == NO_INDEX) {
                pageSlot = allocatePageSlot();
                pageKeys[pageSlot] = pageKey;
                pageMasks[pageSlot] = 0L;
                overflowPriorities[pageSlot] = mixPageKey(pageKey);
                pageDirectoryIndexes[pageSlot] = directoryIndex;
                directorySlots[directoryIndex] = pageSlot;
            }

            int offset = (int) (price & PAGE_MASK);
            long bit = 1L << offset;
            long mask = pageMasks[pageSlot];
            if ((mask & bit) == 0L) {
                pageMasks[pageSlot] = mask | bit;
                if (mask == 0L) {
                    markPageActive(directoryIndex);
                }
            }
            return levelRef(pageSlot, offset);
        }

        private int bestLevel() {
            int directoryIndex = bestCorePageIndex();
            if (directoryIndex == NO_INDEX) {
                if (overflowBestPageSlot == NO_INDEX) {
                    return NO_INDEX;
                }

                rebaseCore(pageKeys[overflowBestPageSlot]);
                directoryIndex = bestCorePageIndex();
            }

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
                if (directoryIndex == NO_INDEX) {
                    unlinkOverflowPage(pageSlot);
                } else {
                    directorySlots[directoryIndex] = NO_INDEX;
                    clearPageActive(directoryIndex);
                }
                releasePageSlot(pageSlot);
            }
        }

        private List<Integer> snapshotLevelSlots() {
            List<Integer> levelSlots = new ArrayList<>();
            if (!coreInitialized && overflowRoot == NO_INDEX) {
                return levelSlots;
            }

            if (buySide) {
                appendCoreLevelsDescending(levelSlots);
                appendOverflowLevelsDescending(levelSlots);
            } else {
                appendCoreLevelsAscending(levelSlots);
                appendOverflowLevelsAscending(levelSlots);
            }
            return levelSlots;
        }

        private Order.Side side() {
            return buySide ? Order.Side.BUY : Order.Side.SELL;
        }

        private void ensureCoreInitialized(long pageKey) {
            if (!coreInitialized) {
                coreBasePageKey = centeredBasePageKey(pageKey);
                coreInitialized = true;
            }
        }

        private int addOverflowLevel(long pageKey, long price) {
            int pageSlot = findOverflowPage(pageKey);
            if (pageSlot == NO_INDEX) {
                pageSlot = allocatePageSlot();
                pageKeys[pageSlot] = pageKey;
                pageMasks[pageSlot] = 0L;
                overflowPriorities[pageSlot] = mixPageKey(pageKey);
                linkOverflowPage(pageSlot);
            }

            int offset = (int) (price & PAGE_MASK);
            pageMasks[pageSlot] |= 1L << offset;
            return levelRef(pageSlot, offset);
        }

        private void appendCoreLevelsDescending(List<Integer> levelSlots) {
            if (!coreInitialized) {
                return;
            }

            for (int directoryIndex = directorySlots.length - 1; directoryIndex >= 0; directoryIndex--) {
                int pageSlot = directorySlots[directoryIndex];
                if (pageSlot != NO_INDEX) {
                    appendPageLevelsDescending(pageSlot, levelSlots);
                }
            }
        }

        private void appendCoreLevelsAscending(List<Integer> levelSlots) {
            if (!coreInitialized) {
                return;
            }

            for (int directoryIndex = 0; directoryIndex < directorySlots.length; directoryIndex++) {
                int pageSlot = directorySlots[directoryIndex];
                if (pageSlot != NO_INDEX) {
                    appendPageLevelsAscending(pageSlot, levelSlots);
                }
            }
        }

        private void appendOverflowLevelsDescending(List<Integer> levelSlots) {
            int stackSize = 0;
            int current = overflowRoot;
            while (current != NO_INDEX || stackSize > 0) {
                while (current != NO_INDEX) {
                    traversalStack[stackSize++] = current;
                    current = overflowRight[current];
                }

                current = traversalStack[--stackSize];
                appendPageLevelsDescending(current, levelSlots);
                current = overflowLeft[current];
            }
        }

        private void appendOverflowLevelsAscending(List<Integer> levelSlots) {
            int stackSize = 0;
            int current = overflowRoot;
            while (current != NO_INDEX || stackSize > 0) {
                while (current != NO_INDEX) {
                    traversalStack[stackSize++] = current;
                    current = overflowLeft[current];
                }

                current = traversalStack[--stackSize];
                appendPageLevelsAscending(current, levelSlots);
                current = overflowRight[current];
            }
        }

        private void appendPageLevelsDescending(int pageSlot, List<Integer> levelSlots) {
            long mask = pageMasks[pageSlot];
            while (mask != 0L) {
                int offset = Long.SIZE - 1 - Long.numberOfLeadingZeros(mask);
                levelSlots.add(levelRef(pageSlot, offset));
                mask &= ~(1L << offset);
            }
        }

        private void appendPageLevelsAscending(int pageSlot, List<Integer> levelSlots) {
            long mask = pageMasks[pageSlot];
            while (mask != 0L) {
                int offset = Long.numberOfTrailingZeros(mask);
                levelSlots.add(levelRef(pageSlot, offset));
                mask &= mask - 1;
            }
        }

        private int directoryIndex(long pageKey) {
            if (!coreInitialized) {
                return NO_INDEX;
            }

            long index = pageKey - coreBasePageKey;
            return index >= 0 && index < directorySlots.length ? (int) index : NO_INDEX;
        }

        private boolean isBetterThanCore(long pageKey) {
            if (!coreInitialized) {
                return true;
            }

            return buySide
                    ? pageKey > coreBasePageKey + directorySlots.length - 1L
                    : pageKey < coreBasePageKey;
        }

        private void rebaseCore(long anchorPageKey) {
            long newBasePageKey = centeredBasePageKey(anchorPageKey);
            if (coreInitialized && newBasePageKey == coreBasePageKey) {
                return;
            }

            int[] oldDirectorySlots = directorySlots;
            directorySlots = filledIntArray(oldDirectorySlots.length, NO_INDEX);
            Arrays.fill(nonEmptyPageWords, 0L);
            Arrays.fill(nonEmptyPageWordSummary, 0L);
            coreBasePageKey = newBasePageKey;
            coreInitialized = true;

            for (int pageSlot : oldDirectorySlots) {
                if (pageSlot == NO_INDEX) {
                    continue;
                }

                int directoryIndex = directoryIndex(pageKeys[pageSlot]);
                if (directoryIndex == NO_INDEX) {
                    pageDirectoryIndexes[pageSlot] = NO_INDEX;
                    linkOverflowPage(pageSlot);
                } else {
                    directorySlots[directoryIndex] = pageSlot;
                    pageDirectoryIndexes[pageSlot] = directoryIndex;
                    markPageActive(directoryIndex);
                }
            }

            moveOverflowPagesIntoCore();
        }

        private long centeredBasePageKey(long anchorPageKey) {
            return anchorPageKey - (directorySlots.length >>> 1);
        }

        private void moveOverflowPagesIntoCore() {
            if (overflowRoot == NO_INDEX) {
                return;
            }

            List<Integer> pagesToPromote = new ArrayList<>();
            int stackSize = 0;
            int current = overflowRoot;
            while (current != NO_INDEX || stackSize > 0) {
                while (current != NO_INDEX) {
                    traversalStack[stackSize++] = current;
                    current = overflowLeft[current];
                }

                current = traversalStack[--stackSize];
                if (directoryIndex(pageKeys[current]) != NO_INDEX) {
                    pagesToPromote.add(current);
                }
                current = overflowRight[current];
            }

            for (int pageSlot : pagesToPromote) {
                unlinkOverflowPage(pageSlot);
                int directoryIndex = directoryIndex(pageKeys[pageSlot]);
                directorySlots[directoryIndex] = pageSlot;
                pageDirectoryIndexes[pageSlot] = directoryIndex;
                markPageActive(directoryIndex);
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

        private int bestCorePageIndex() {
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

        private int findOverflowPage(long pageKey) {
            int current = overflowRoot;
            while (current != NO_INDEX) {
                long currentKey = pageKeys[current];
                if (pageKey < currentKey) {
                    current = overflowLeft[current];
                } else if (pageKey > currentKey) {
                    current = overflowRight[current];
                } else {
                    return current;
                }
            }
            return NO_INDEX;
        }

        private void linkOverflowPage(int pageSlot) {
            overflowLeft[pageSlot] = NO_INDEX;
            overflowRight[pageSlot] = NO_INDEX;
            overflowParent[pageSlot] = NO_INDEX;

            if (overflowRoot == NO_INDEX) {
                overflowRoot = pageSlot;
                overflowBestPageSlot = pageSlot;
                return;
            }

            long pageKey = pageKeys[pageSlot];
            int current = overflowRoot;
            while (true) {
                if (pageKey < pageKeys[current]) {
                    int left = overflowLeft[current];
                    if (left == NO_INDEX) {
                        overflowLeft[current] = pageSlot;
                        overflowParent[pageSlot] = current;
                        break;
                    }
                    current = left;
                } else {
                    int right = overflowRight[current];
                    if (right == NO_INDEX) {
                        overflowRight[current] = pageSlot;
                        overflowParent[pageSlot] = current;
                        break;
                    }
                    current = right;
                }
            }

            bubbleUpOverflow(pageSlot);
            if (isBetterPage(pageSlot, overflowBestPageSlot)) {
                overflowBestPageSlot = pageSlot;
            }
        }

        private void unlinkOverflowPage(int pageSlot) {
            while (overflowLeft[pageSlot] != NO_INDEX || overflowRight[pageSlot] != NO_INDEX) {
                int left = overflowLeft[pageSlot];
                int right = overflowRight[pageSlot];
                if (left == NO_INDEX) {
                    rotateLeft(pageSlot);
                } else if (right == NO_INDEX) {
                    rotateRight(pageSlot);
                } else if (overflowPriorities[left] < overflowPriorities[right]) {
                    rotateRight(pageSlot);
                } else {
                    rotateLeft(pageSlot);
                }
            }

            int parent = overflowParent[pageSlot];
            if (parent == NO_INDEX) {
                overflowRoot = NO_INDEX;
            } else if (overflowLeft[parent] == pageSlot) {
                overflowLeft[parent] = NO_INDEX;
            } else {
                overflowRight[parent] = NO_INDEX;
            }

            if (overflowBestPageSlot == pageSlot) {
                overflowBestPageSlot = overflowRoot == NO_INDEX ? NO_INDEX : extremeOverflowPage(overflowRoot);
            }
        }

        private void bubbleUpOverflow(int pageSlot) {
            while (true) {
                int parent = overflowParent[pageSlot];
                if (parent == NO_INDEX || overflowPriorities[parent] <= overflowPriorities[pageSlot]) {
                    return;
                }

                if (overflowLeft[parent] == pageSlot) {
                    rotateRight(parent);
                } else {
                    rotateLeft(parent);
                }
            }
        }

        private void rotateLeft(int pageSlot) {
            int pivot = overflowRight[pageSlot];
            int pivotLeft = overflowLeft[pivot];
            int parent = overflowParent[pageSlot];

            overflowRight[pageSlot] = pivotLeft;
            if (pivotLeft != NO_INDEX) {
                overflowParent[pivotLeft] = pageSlot;
            }

            overflowLeft[pivot] = pageSlot;
            overflowParent[pageSlot] = pivot;
            overflowParent[pivot] = parent;

            if (parent == NO_INDEX) {
                overflowRoot = pivot;
            } else if (overflowLeft[parent] == pageSlot) {
                overflowLeft[parent] = pivot;
            } else {
                overflowRight[parent] = pivot;
            }
        }

        private void rotateRight(int pageSlot) {
            int pivot = overflowLeft[pageSlot];
            int pivotRight = overflowRight[pivot];
            int parent = overflowParent[pageSlot];

            overflowLeft[pageSlot] = pivotRight;
            if (pivotRight != NO_INDEX) {
                overflowParent[pivotRight] = pageSlot;
            }

            overflowRight[pivot] = pageSlot;
            overflowParent[pageSlot] = pivot;
            overflowParent[pivot] = parent;

            if (parent == NO_INDEX) {
                overflowRoot = pivot;
            } else if (overflowLeft[parent] == pageSlot) {
                overflowLeft[parent] = pivot;
            } else {
                overflowRight[parent] = pivot;
            }
        }

        private boolean isBetterPage(int candidatePageSlot, int currentBestPageSlot) {
            if (currentBestPageSlot == NO_INDEX) {
                return true;
            }

            long candidateKey = pageKeys[candidatePageSlot];
            long currentBestKey = pageKeys[currentBestPageSlot];
            return buySide ? candidateKey > currentBestKey : candidateKey < currentBestKey;
        }

        private int extremeOverflowPage(int startPageSlot) {
            int current = startPageSlot;
            if (buySide) {
                while (overflowRight[current] != NO_INDEX) {
                    current = overflowRight[current];
                }
            } else {
                while (overflowLeft[current] != NO_INDEX) {
                    current = overflowLeft[current];
                }
            }
            return current;
        }

        private int mixPageKey(long pageKey) {
            long mixed = pageKey + 0x9E3779B97F4A7C15L;
            mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
            mixed ^= mixed >>> 31;
            return (int) mixed;
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
            overflowLeft[pageSlot] = NO_INDEX;
            overflowRight[pageSlot] = NO_INDEX;
            overflowParent[pageSlot] = NO_INDEX;
            pageNextFree[pageSlot] = freePageSlot;
            freePageSlot = pageSlot;
        }

        private void growPageStorage() {
            int newCapacity = pageSlotCapacity << 1;
            pageKeys = Arrays.copyOf(pageKeys, newCapacity);
            pageMasks = Arrays.copyOf(pageMasks, newCapacity);
            pageDirectoryIndexes = OrderBookImpl.growIntArray(pageDirectoryIndexes, newCapacity);
            overflowLeft = OrderBookImpl.growIntArray(overflowLeft, newCapacity);
            overflowRight = OrderBookImpl.growIntArray(overflowRight, newCapacity);
            overflowParent = OrderBookImpl.growIntArray(overflowParent, newCapacity);
            overflowPriorities = Arrays.copyOf(overflowPriorities, newCapacity);
            pageNextFree = OrderBookImpl.growIntArray(pageNextFree, newCapacity);
            traversalStack = Arrays.copyOf(traversalStack, newCapacity);
            levelHeads = OrderBookImpl.growIntArray(levelHeads, newCapacity << PAGE_SHIFT);
            levelTails = OrderBookImpl.growIntArray(levelTails, newCapacity << PAGE_SHIFT);
            pageSlotCapacity = newCapacity;
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
}
