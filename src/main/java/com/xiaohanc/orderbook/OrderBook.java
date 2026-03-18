package com.xiaohanc.orderbook;

import java.util.List;
import java.util.NoSuchElementException;

public interface OrderBook {
    void addOrder(long id, Order.Side side, long price, long quantity);

    /**
     * @throws NoSuchElementException if order doesn't exist
     */
    void cancelOrder(long id);

    /**
     * @throws NoSuchElementException if order doesn't exist
     */
    void modifyOrder(long id, long newPrice, long newQuantity);

    List<Order> getBids();
    List<Order> getAsks();

    @FunctionalInterface
    interface OrderMatchListener {
        /**
         * Called whenever a match occurs in the orderbook.
         */
        void onMatch(long makerOrderId, long takerOrderId, long price, long quantity);
    }
}
