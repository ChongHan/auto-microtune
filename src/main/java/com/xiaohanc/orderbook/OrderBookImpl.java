package com.xiaohanc.orderbook;

import java.util.*;

public class OrderBookImpl implements OrderBook {
    private final List<Order> bids = new ArrayList<>();
    private final List<Order> asks = new ArrayList<>();
    private final Map<Long, Order> orderById = new HashMap<>();
    private final OrderMatchListener listener;

    public OrderBookImpl(OrderMatchListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    @Override
    public void addOrder(long id, Order.Side side, long price, long quantity) {
        long remainingQuantity;
        if (side == Order.Side.BUY) {
            remainingQuantity = matchOrder(id, side, price, quantity, asks);
            if (remainingQuantity > 0) {
                Order order = new Order(id, side, price, remainingQuantity);
                bids.add(order);
                orderById.put(id, order);
                bids.sort((o1, o2) -> Long.compare(o2.price(), o1.price())); // Descending
            }
        } else {
            remainingQuantity = matchOrder(id, side, price, quantity, bids);
            if (remainingQuantity > 0) {
                Order order = new Order(id, side, price, remainingQuantity);
                asks.add(order);
                orderById.put(id, order);
                asks.sort(Comparator.comparingLong(Order::price)); // Ascending
            }
        }
    }

    @Override
    public void cancelOrder(long id) {
        Order order = orderById.remove(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        List<Order> sideList = (order.side() == Order.Side.BUY) ? bids : asks;
        sideList.removeIf(o -> o.id() == id);
    }

    @Override
    public void modifyOrder(long id, long newPrice, long newQuantity) {
        Order order = orderById.get(id);
        if (order == null) {
            throw new NoSuchElementException("Order ID not found: " + id);
        }

        Order.Side side = order.side();
        cancelOrder(id);
        addOrder(id, side, newPrice, newQuantity);
    }

    private long matchOrder(long incomingId, Order.Side incomingSide, long incomingPrice, long incomingQuantity, List<Order> oppositeSide) {
        long remainingTakerQuantity = incomingQuantity;
        ListIterator<Order> iterator = oppositeSide.listIterator();
        
        while (iterator.hasNext() && remainingTakerQuantity > 0) {
            Order bookOrder = iterator.next();
            boolean canMatch = incomingSide == Order.Side.BUY
                    ? incomingPrice >= bookOrder.price()
                    : incomingPrice <= bookOrder.price();

            if (canMatch) {
                long matchedQuantity = Math.min(remainingTakerQuantity, bookOrder.quantity());
                
                listener.onMatch(
                    bookOrder.id(), 
                    incomingId, 
                    bookOrder.price(), 
                    matchedQuantity
                );

                remainingTakerQuantity -= matchedQuantity;
                long remainingMakerQuantity = bookOrder.quantity() - matchedQuantity;

                if (remainingMakerQuantity == 0) {
                    orderById.remove(bookOrder.id());
                    iterator.remove();
                } else {
                    // Update the book order with new quantity
                    Order updatedMaker = new Order(bookOrder.id(), bookOrder.side(), bookOrder.price(), remainingMakerQuantity);
                    orderById.put(bookOrder.id(), updatedMaker);
                    iterator.set(updatedMaker);
                }
            } else {
                // Since the opposite side is sorted, if we can't match this one, we can't match any further
                break;
            }
        }
        return remainingTakerQuantity;
    }

    @Override
    public List<Order> getBids() {
        return Collections.unmodifiableList(bids);
    }

    @Override
    public List<Order> getAsks() {
        return Collections.unmodifiableList(asks);
    }
}
