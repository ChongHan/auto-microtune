# Hybrid OrderBook Visualization

This document describes the current hybrid order-book layout implemented in
[`src/main/java/com/xiaohanc/orderbook/OrderBookImpl.java`](src/main/java/com/xiaohanc/orderbook/OrderBookImpl.java).

It is intentionally more concrete than `EXPERIMENT_SUMMARY.md`: the goal here is to show the exact
shape of the arrays, page indices, FIFO links, and transitions for a specific sequence of orders.

## Scope

The implementation has four major layers:

1. **Global order storage**
   Orders live in primitive arrays indexed by `orderSlot`.
2. **Per-side price structure**
   Each side (`bids`, `asks`) owns a `SideBook`.
3. **Dense core window**
   The hot price region is stored in a fixed-size dense directory with bitmap summaries.
4. **Sparse overflow**
   Pages outside the current dense window live in a sparse overflow tree.

The hybrid design preserves the fast dense-path from commit `f416e48`, but bounds its range and
spills far-out pages into overflow instead of letting the dense directory grow with total price span.

## Performance Snapshot

This hybrid design is a robustness-oriented follow-up to the faster dense-only design from commit
`f416e48`. It was benchmarked on **March 25, 2026** with:

```bash
./gradlew benchmark -PrunArgs='-rf json -rff candidate.json -wi 3 -i 5'
```

The measured JMH result for `OrderBookBenchmark.replayOrders` was:

- current hybrid working tree: `63,516.268 us/op ± 3,910.296`
- dense rebasing baseline from `f416e48`: `59,394.0308 us/op`
- delta versus `f416e48`: `+6.94%` slower

That tradeoff is the point of the hybrid design:

- it gives up some peak benchmark speed
- in exchange, it removes the dense-only design's dependence on the full observed price span
- it behaves more predictably when a mostly local book has a small number of distant tail prices

So the hybrid should be read as a **robustness improvement with a measurable performance cost**, not
as a new benchmark winner over `f416e48`.

## Constants and Mapping Rules

The relevant constants are:

- `PAGE_SHIFT = 6`
- `PAGE_MASK = 63`
- `CORE_DIRECTORY_CAPACITY = 16384`
- each page covers `64` exact prices

The key formulas are:

| Quantity | Formula |
| :--- | :--- |
| `pageKey` | `price >> 6` |
| `offset` | `price & 63` |
| `levelSlot` | `(pageSlot << 6) | offset` |
| dense `directoryIndex` | `pageKey - coreBasePageKey` |
| initial centered base | `pageKey - 8192` |

The dense core therefore spans exactly:

- `16384 pages * 64 prices/page = 1,048,576 prices`

The dense width is fixed. The base of the window is not fixed and can move when the book recenters.

## Data Structure Inventory

### Global Order Storage

These arrays live on `OrderBookImpl` and are shared by both sides:

| Array | Meaning |
| :--- | :--- |
| `orderIds[orderSlot]` | external order id |
| `orderQuantities[orderSlot]` | remaining quantity |
| `orderLevels[orderSlot]` | encoded `levelSlot`; ask orders set the sign bit |
| `orderPrev[orderSlot]` | previous order in the exact-price FIFO |
| `orderNext[orderSlot]` | next order in the exact-price FIFO |
| `orderMapSlots[orderSlot]` | probe location inside `OrderMap` |

### Dense Core State Per Side

These arrays belong to `SideBook`:

| Array / Field | Meaning |
| :--- | :--- |
| `coreBasePageKey` | left edge of the dense window |
| `directorySlots[directoryIndex]` | maps dense page index to `pageSlot` |
| `nonEmptyPageWords` | first-level bitmap of active dense pages |
| `nonEmptyPageWordSummary` | second-level bitmap over `nonEmptyPageWords` |

### Page Storage

Dense and overflow pages share the same page storage arrays:

| Array | Meaning |
| :--- | :--- |
| `pageKeys[pageSlot]` | logical page key |
| `pageMasks[pageSlot]` | 64-bit bitmap of active prices within the page |
| `pageDirectoryIndexes[pageSlot]` | dense index if the page is in core, otherwise `-1` |
| `levelHeads[levelSlot]` | first `orderSlot` at that exact price |
| `levelTails[levelSlot]` | last `orderSlot` at that exact price |

### Overflow Tree

Overflow pages are linked by:

| Array / Field | Meaning |
| :--- | :--- |
| `overflowRoot` | root page slot |
| `overflowBestPageSlot` | cached best overflow page for that side |
| `overflowLeft[pageSlot]` | left child |
| `overflowRight[pageSlot]` | right child |
| `overflowParent[pageSlot]` | parent |
| `overflowPriorities[pageSlot]` | treap priority derived from `pageKey` |

## Worked Example

We will walk through these four commands on the **bid side**:

1. `addOrder(1001, BUY, 150, 10)`
2. `addOrder(1002, BUY, -1_000_000, 5)`
3. `addOrder(1003, BUY, 2_000_000, 7)`
4. `addOrder(2001, SELL, 1_999_999, 7)`

This sequence is useful because it shows:

- a normal in-core price
- a far lower outlier that goes to overflow
- a far higher outlier that forces a rebase
- a match that fully removes the dense best level

For these order ids, `OrderMap.mix(id) == id` because the ids are small enough that the mix reduces
to the original integer. With initial map capacity `16384`, the initial probe index is therefore
equal to the id itself.

## Step 1: Add `BUY 1001 @ 150 x 10`

### Price Mapping

- `price = 150`
- `pageKey = 150 >> 6 = 2`
- `offset = 150 & 63 = 22`

The dense core is not initialized yet, so:

- `coreBasePageKey = centeredBasePageKey(2) = 2 - 8192 = -8190`
- `directoryIndex = 2 - (-8190) = 8192`

The page does not exist yet, so the side book allocates:

- `pageSlot = 0`
- `levelSlot = (0 << 6) | 22 = 22`

### Dense-Core Arrays After Step 1

| Structure | Index | Value | Meaning |
| :--- | :--- | :--- | :--- |
| `coreBasePageKey` | | `-8190` | centered dense base |
| `directorySlots` | `8192` | `0` | dense page `8192` points to `pageSlot 0` |
| `pageKeys` | `0` | `2` | page slot `0` stores `pageKey 2` |
| `pageMasks` | `0` | `1 << 22 = 4194304` | price `150` is active inside page `0` |
| `pageDirectoryIndexes` | `0` | `8192` | page `0` belongs to dense index `8192` |

### Dense Bitmap State After Step 1

The dense page index is `8192`.

- `pageWordIndex = 8192 >>> 6 = 128`
- bit within that word = `8192 & 63 = 0`
- `summaryIndex = 128 >>> 6 = 2`
- bit within summary word = `128 & 63 = 0`

So:

| Structure | Index | Value |
| :--- | :--- | :--- |
| `nonEmptyPageWords` | `128` | `1` |
| `nonEmptyPageWordSummary` | `2` | `1` |

### Exact Level Queue After Step 1

| Array | Index | Value |
| :--- | :--- | :--- |
| `levelHeads` | `22` | `0` |
| `levelTails` | `22` | `0` |

This means price `150` has a one-element FIFO whose only order is `orderSlot 0`.

### Global Order Storage After Step 1

| Array | Index | Value |
| :--- | :--- | :--- |
| `orderIds` | `0` | `1001` |
| `orderQuantities` | `0` | `10` |
| `orderLevels` | `0` | `22` |
| `orderPrev` | `0` | `-1` |
| `orderNext` | `0` | `-1` |
| `orderMapSlots` | `0` | `1001` |

### OrderMap State After Step 1

| Array | Index | Value |
| :--- | :--- | :--- |
| `keys` | `1001` | `1001` |
| `values` | `1001` | `0` |

## Step 2: Add `BUY 1002 @ -1_000_000 x 5`

### Price Mapping

- `price = -1_000_000`
- `pageKey = -1_000_000 >> 6 = -15625`
- `offset = -1_000_000 & 63 = 0`

Dense lookup uses the existing base:

- `directoryIndex = -15625 - (-8190) = -7435`

That is outside the dense core. For the buy side, this page is **not** better than the current dense
window because it is far lower than the current bid region. Therefore it goes to overflow.

The side book allocates:

- `pageSlot = 1`
- `levelSlot = (1 << 6) | 0 = 64`

Its deterministic overflow priority is:

- `overflowPriorities[1] = 1213185492`

Because overflow was empty, this page becomes both:

- `overflowRoot = 1`
- `overflowBestPageSlot = 1`

### Overflow State After Step 2

| Structure | Index | Value |
| :--- | :--- | :--- |
| `overflowRoot` | | `1` |
| `overflowBestPageSlot` | | `1` |
| `pageKeys` | `1` | `-15625` |
| `pageMasks` | `1` | `1` |
| `pageDirectoryIndexes` | `1` | `-1` |
| `overflowLeft` | `1` | `-1` |
| `overflowRight` | `1` | `-1` |
| `overflowParent` | `1` | `-1` |
| `overflowPriorities` | `1` | `1213185492` |

### Global Order Storage After Step 2

| Array | Index | Value |
| :--- | :--- | :--- |
| `orderIds` | `1` | `1002` |
| `orderQuantities` | `1` | `5` |
| `orderLevels` | `1` | `64` |
| `orderPrev` | `1` | `-1` |
| `orderNext` | `1` | `-1` |
| `orderMapSlots` | `1` | `1002` |

### Visible Bid Order After Step 2

`getBids()` will return:

1. dense core levels in descending order
2. overflow levels in descending order

So the visible order is:

1. `1001 @ 150 x 10`
2. `1002 @ -1_000_000 x 5`

## Step 3: Add `BUY 1003 @ 2_000_000 x 7`

### Price Mapping

- `price = 2_000_000`
- `pageKey = 2_000_000 >> 6 = 31250`
- `offset = 2_000_000 & 63 = 0`

Using the current dense base:

- current dense range is `[-8190, 8193]`
- `31250` is outside the range

For the buy side, this page **is** better than the current dense window because it is above the dense
top. So the side book recenters the dense window before storing the new order.

### Rebase Operation

The new centered base is:

- `coreBasePageKey = 31250 - 8192 = 23058`

The old dense page (`pageSlot 0`, `pageKey 2`) no longer fits in the new dense range `[23058, 39441]`,
so it is moved into overflow.

Overflow already contains `pageSlot 1`.

The priority of `pageSlot 0` is:

- `overflowPriorities[0] = 479680206`

Since `479680206 < 1213185492`, the treap rotations promote `pageSlot 0` above `pageSlot 1`.

### Overflow Tree After Rebase, Before Adding The New Best Price

| Slot | `pageKey` | `left` | `right` | `parent` | priority |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `0` | `2` | `1` | `-1` | `-1` | `479680206` |
| `1` | `-15625` | `-1` | `-1` | `0` | `1213185492` |

So:

- `overflowRoot = 0`
- `overflowBestPageSlot = 0`

This is correct for the buy side because page `2` is a better bid than page `-15625`.

### Add The New Best Page Into Dense Core

The new page is allocated as:

- `pageSlot = 2`
- `levelSlot = (2 << 6) | 0 = 128`
- `pageDirectoryIndexes[2] = 8192`
- `directorySlots[8192] = 2`
- `pageMasks[2] = 1`

Its stored priority is:

- `overflowPriorities[2] = -654148865`

That priority is not used immediately because `pageSlot 2` is in the dense core, not overflow.

### Dense-Core State After Step 3

| Structure | Index | Value |
| :--- | :--- | :--- |
| `coreBasePageKey` | | `23058` |
| `directorySlots` | `8192` | `2` |
| `pageKeys` | `2` | `31250` |
| `pageMasks` | `2` | `1` |
| `pageDirectoryIndexes` | `2` | `8192` |
| `nonEmptyPageWords` | `128` | `1` |
| `nonEmptyPageWordSummary` | `2` | `1` |

### Global Order Storage After Step 3

| Array | Index | Value |
| :--- | :--- | :--- |
| `orderIds` | `2` | `1003` |
| `orderQuantities` | `2` | `7` |
| `orderLevels` | `2` | `128` |
| `orderPrev` | `2` | `-1` |
| `orderNext` | `2` | `-1` |
| `orderMapSlots` | `2` | `1003` |

### Visible Bid Order After Step 3

The visible order is now:

1. `1003 @ 2_000_000 x 7` from dense core
2. `1001 @ 150 x 10` from overflow root
3. `1002 @ -1_000_000 x 5` from overflow left child

That is exactly the intended buy-side price ordering.

## Step 4: Add `SELL 2001 @ 1_999_999 x 7`

This incoming sell order crosses the best bid because:

- `incomingPrice = 1_999_999`
- best resting bid = `2_000_000`
- sell orders cross when `incomingPrice <= restingPrice`

### Match Loop

`matchOrder(...)` asks the bid side for `bestLevel()`:

1. `bestCorePageIndex()` finds dense index `8192`
2. `directorySlots[8192] = 2`
3. `pageMasks[2] = 1`
4. best offset inside that page is `0`
5. best `levelSlot = 128`

Then:

- `priceOf(128) = (31250 << 6) | 0 = 2_000_000`
- `makerSlot = levelHeads[128] = 2`

The match is therefore:

- maker = order `1003`
- taker = order `2001`
- price = `2_000_000`
- quantity = `7`

### Effects Of The Full Fill

Because the maker is fully consumed:

1. `orderById.removeValue(2, orderMapSlots)` removes id `1003`
2. `removeMatchedHead(...)` clears the exact-price FIFO
3. `book.removeLevel(128)` removes the page bit
4. the page becomes empty, so `directorySlots[8192] = -1`
5. `nonEmptyPageWords[128] = 0`
6. `nonEmptyPageWordSummary[2] = 0`
7. `releasePageSlot(2)` returns `pageSlot 2` to the free list

Since the incoming sell was fully filled, it never becomes a resting ask.

### State Immediately After The Match

Dense core:

| Structure | Index | Value |
| :--- | :--- | :--- |
| `directorySlots` | `8192` | `-1` |
| `nonEmptyPageWords` | `128` | `0` |
| `nonEmptyPageWordSummary` | `2` | `0` |

Released page:

| Structure | Index | Value |
| :--- | :--- | :--- |
| `pageMasks` | `2` | `0` |
| `pageDirectoryIndexes` | `2` | `-1` |
| `pageNextFree` | `2` | `-1` |
| `freePageSlot` | | `2` |

Orders still resting:

1. `1001 @ 150 x 10`
2. `1002 @ -1_000_000 x 5`

Both are in overflow at this moment.

### What Happens On The Next Best-Level Lookup

If another sell arrives immediately after this, the bid side will run `bestLevel()` again.

At that point:

- dense core is empty
- `overflowBestPageSlot = 0`
- `pageKeys[0] = 2`

So the side book will rebase around `pageKey 2`:

- `coreBasePageKey = 2 - 8192 = -8190`

Then `moveOverflowPagesIntoCore()` will promote:

- `pageSlot 0` back into dense core at `directoryIndex 8192`

and leave:

- `pageSlot 1` in overflow, because `pageKey -15625` still lies outside the centered dense range

This is the core feedback loop of the hybrid design:

- dense window goes where the current best region is
- outliers remain representable without forcing unbounded dense growth

## Signed Prices And Page Boundaries

The implementation handles negative prices correctly because it relies on:

- arithmetic right-shift for `pageKey`
- bit masking for `offset`

Examples:

| Price | `pageKey = price >> 6` | `offset = price & 63` |
| :--- | :--- | :--- |
| `-1` | `-1` | `63` |
| `-64` | `-1` | `0` |
| `-65` | `-2` | `63` |
| `0` | `0` | `0` |
| `63` | `0` | `63` |
| `64` | `1` | `0` |

So negative and positive prices share the same page machinery cleanly. The only thing that changes
is the signed value of `pageKey`.

The regression tests in
[`src/test/java/com/xiaohanc/orderbook/OrderBookTest.java`](src/test/java/com/xiaohanc/orderbook/OrderBookTest.java)
cover both:

- far-out sparse prices
- sign flips across negative and positive prices

## Operational Summary

For the current hybrid implementation, the flow is:

1. **Match first** using `bestLevel()` on the opposite side.
2. **If residual quantity remains**, map the price to `(pageKey, offset)`.
3. **If the page fits in the dense core**, use the dense directory and bitmap path.
4. **If the page is outside the dense core**:
   - rebase if it is a new better page for that side
   - otherwise insert it into overflow
5. **Append the order** to the exact-price FIFO using `levelHeads`, `levelTails`, `orderPrev`, `orderNext`.
6. **Index the order id** in `OrderMap`.

The important property is that the data structure cost is now:

- proportional to active pages and the fixed dense-core width
- not proportional to the full min-to-max price range ever seen

That is the reason for the hybrid layout.
