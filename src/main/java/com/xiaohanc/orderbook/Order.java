package com.xiaohanc.orderbook;

public record Order(long id, Side side, long price, long quantity) {
    public enum Side {
        BUY, SELL
    }
}
