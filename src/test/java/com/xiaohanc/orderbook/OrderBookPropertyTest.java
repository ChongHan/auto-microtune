package com.xiaohanc.orderbook;

import net.jqwik.api.*;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;
import org.junit.jupiter.api.Assertions;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class OrderBookPropertyTest {

    @Property
    void allOperationsShouldMaintainInvariants(@ForAll("orderBookActions") ActionChain<OrderBookState> actionChain) {
        actionChain.run();
    }

    @Provide
    Arbitrary<ActionChain<OrderBookState>> orderBookActions() {
        return ActionChain.startWith(OrderBookState::new)
                .withAction(new AddAction())
                .withAction(new CancelAction())
                .withAction(new ModifyAction());
    }

    static class OrderBookState {
        final AtomicLong matchedQty = new AtomicLong(0);
        final OrderBook orderBook = new OrderBookImpl((mId, tId, p, q) -> matchedQty.addAndGet(q));
        long totalAdded = 0;
        long totalCancelled = 0;

        void verifyInvariants() {
            verifySorting();
            verifyNoMatchable();
            verifyUniqueIds();
            verifyPositiveQuantities();
            verifyQuantityConservation();
        }

        private void verifySorting() {
            List<Order> bids = orderBook.getBids();
            for (int i = 0; i < bids.size() - 1; i++) {
                Assertions.assertTrue(bids.get(i).price() >= bids.get(i + 1).price(),
                        String.format("Bids not sorted: %d < %d", bids.get(i).price(), bids.get(i + 1).price()));
            }
            List<Order> asks = orderBook.getAsks();
            for (int i = 0; i < asks.size() - 1; i++) {
                Assertions.assertTrue(asks.get(i).price() <= asks.get(i + 1).price(),
                        String.format("Asks not sorted: %d > %d", asks.get(i).price(), asks.get(i + 1).price()));
            }
        }

        private void verifyNoMatchable() {
            List<Order> bids = orderBook.getBids();
            List<Order> asks = orderBook.getAsks();
            if (!bids.isEmpty() && !asks.isEmpty()) {
                Assertions.assertTrue(bids.getFirst().price() < asks.getFirst().price(),
                        String.format("Matchable orders remain: Bid %d >= Ask %d", bids.getFirst().price(), asks.getFirst().price()));
            }
        }

        private void verifyUniqueIds() {
            List<Order> allOrders = new ArrayList<>(orderBook.getBids());
            allOrders.addAll(orderBook.getAsks());
            long uniqueIds = allOrders.stream().mapToLong(Order::id).distinct().count();
            Assertions.assertEquals(allOrders.size(), (int) uniqueIds, "Duplicate order IDs found in book");
        }

        private void verifyPositiveQuantities() {
            orderBook.getBids().forEach(o -> Assertions.assertTrue(o.quantity() > 0, "Bid quantity must be positive"));
            orderBook.getAsks().forEach(o -> Assertions.assertTrue(o.quantity() > 0, "Ask quantity must be positive"));
        }

        private void verifyQuantityConservation() {
            long qtyInBook = orderBook.getBids().stream().mapToLong(Order::quantity).sum() +
                             orderBook.getAsks().stream().mapToLong(Order::quantity).sum();
            Assertions.assertEquals(totalAdded, qtyInBook + totalCancelled + 2 * matchedQty.get(),
                    String.format("Quantity conservation failed: Initial(%d) != Book(%d) + Cancelled(%d) + 2*Matched(%d)",
                            totalAdded, qtyInBook, totalCancelled, matchedQty.get()));
        }

        Optional<Order> findOrder(long id) {
            return orderBook.getBids().stream().filter(o -> o.id() == id).findFirst()
                    .or(() -> orderBook.getAsks().stream().filter(o -> o.id() == id).findFirst());
        }

        Set<Long> getAllOrderIds() {
            Set<Long> ids = new HashSet<>();
            orderBook.getBids().forEach(o -> ids.add(o.id()));
            orderBook.getAsks().forEach(o -> ids.add(o.id()));
            return ids;
        }
    }

    static class AddAction implements Action.Dependent<OrderBookState> {
        @Override
        public Arbitrary<Transformer<OrderBookState>> transformer(OrderBookState state) {
            Arbitrary<Long> nextId = Arbitraries.longs().between(1, 1000).filter(id -> !state.getAllOrderIds().contains(id));
            Arbitrary<Order.Side> sides = Arbitraries.of(Order.Side.class);
            Arbitrary<Long> prices = Arbitraries.longs().between(1, 100);
            Arbitrary<Long> quantities = Arbitraries.longs().between(1, 1000);

            return Combinators.combine(nextId, sides, prices, quantities).as((id, side, price, quantity) -> s -> {
                s.totalAdded += quantity;
                s.orderBook.addOrder(id, side, price, quantity);
                s.verifyInvariants();
                return s;
            });
        }
    }

    static class CancelAction implements Action.Dependent<OrderBookState> {
        @Override
        public boolean precondition(OrderBookState state) {
            return !state.getAllOrderIds().isEmpty();
        }

        @Override
        public Arbitrary<Transformer<OrderBookState>> transformer(OrderBookState state) {
            return Arbitraries.of(state.getAllOrderIds()).map(id -> s -> {
                Order target = s.findOrder(id).orElseThrow();
                s.totalCancelled += target.quantity();
                s.orderBook.cancelOrder(id);
                s.verifyInvariants();
                return s;
            });
        }
    }

    static class ModifyAction implements Action.Dependent<OrderBookState> {
        @Override
        public boolean precondition(OrderBookState state) {
            return !state.getAllOrderIds().isEmpty();
        }

        @Override
        public Arbitrary<Transformer<OrderBookState>> transformer(OrderBookState state) {
            return Arbitraries.of(state.getAllOrderIds()).flatMap(id -> {
                Arbitrary<Long> prices = Arbitraries.longs().between(1, 100);
                Arbitrary<Long> quantities = Arbitraries.longs().between(1, 1000);

                return Combinators.combine(prices, quantities).as((p, q) -> s -> {
                    Order target = s.findOrder(id).orElseThrow();
                    s.totalCancelled += target.quantity();
                    s.totalAdded += q;
                    s.orderBook.modifyOrder(id, p, q);
                    s.verifyInvariants();
                    return s;
                });
            });
        }
    }
}
