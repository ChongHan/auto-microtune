package com.xiaohanc.orderbook;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1)
public class OrderBookBenchmark {

    private List<OrderBookCommand> commands;
    private OrderBook orderBook;

    @Setup(Level.Trial)
    public void setup() {
        int numCommands = 1_000_000;
        commands = new ArrayList<>(numCommands);
        Random random = new Random(42);
        
        int adds = 0, modifies = 0, cancels = 0;
        class Counter { int count = 0; }
        Counter matchCounter = new Counter();
        OrderBook simBook = new OrderBookImpl((mId, tId, p, q) -> matchCounter.count++);
        
        long nextOrderId = 0;

        for (int i = 0; i < numCommands; i++) {
            List<Order> all = new ArrayList<>(simBook.getBids());
            all.addAll(simBook.getAsks());

            double prob = random.nextDouble();
            OrderBookCommand cmd;
            if (prob < 0.83 || all.isEmpty()) {
                Order.Side side = random.nextBoolean() ? Order.Side.BUY : Order.Side.SELL;
                long price;
                if ((side == Order.Side.BUY)) {
                    price = nextSkewedValue(random, 1, 105, 105, 11.5);
                } else {
                    price = nextSkewedValue(random, 95, 200, 95, 11.5);
                }
                cmd = new OrderBookCommand.Add(nextOrderId++, side, price, nextValue(random, 1, 100));
                adds++;
            } else if (prob < 0.95) {
                Order target = all.get(random.nextInt(all.size()));
                cmd = new OrderBookCommand.Modify(target.id(), nextValue(random, 1, 100), nextValue(random, 1, 100));
                modifies++;
            } else {
                Order target = all.get(random.nextInt(all.size()));
                cmd = new OrderBookCommand.Cancel(target.id());
                cancels++;
            }
            commands.add(cmd);
            cmd.execute(simBook);
        }

        System.out.printf("Generated %d commands: Adds=%d, Modifies=%d, Cancels=%d, Matches=%d, OrderBook-Size=%d/%d%n",
            numCommands, adds, modifies, cancels, matchCounter.count,
            simBook.getBids().size(), simBook.getAsks().size());
    }

    private long nextValue(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private long nextSkewedValue(Random random, double low, double high, double mode, double skew) {
        double u = random.nextDouble();
        double val;
        if (mode == high) {
            val = high - (high - low) * Math.pow(u, skew);
        } else if (mode == low) {
            val = low + (high - low) * Math.pow(u, skew);
        } else {
            val = low + (high - low) * u;
        }
        return Math.round(val);
    }

    @Setup(Level.Invocation)
    public void iterationSetup() {
        orderBook = new OrderBookImpl((mId, tId, p, q) -> {});
    }

    @Benchmark
    public void replayOrders() {
        for (OrderBookCommand cmd : commands) {
            cmd.execute(orderBook);
        }
    }

    public static void main(String[] args) throws Exception {
        String libPath = extractProfiler();
        
        Options commandLineOptions = new CommandLineOptions(args);
        
        String profilerOptions = "libPath=" + libPath + ";output=text;dir=profiler-results";
        
        Options options = new OptionsBuilder()
            .parent(commandLineOptions)
            .include(OrderBookBenchmark.class.getSimpleName())
            .addProfiler(AsyncProfiler.class, profilerOptions)
            .jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED")
            .build();

        new Runner(options).run();
    }

    private static String extractProfiler() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String osDir = os.contains("mac") ? "macos" : 
                      os.contains("linux") ? (arch.contains("64") && !arch.contains("aarch") ? "linux-x64" : "linux-arm64") : null;
        
        if (osDir == null) throw new RuntimeException("Unsupported OS: " + os);

        String libName = "libasyncProfiler.so";
        Path targetPath = Files.createTempDirectory("async-profiler").resolve(libName);
        try (InputStream is = OrderBookBenchmark.class.getResourceAsStream("/" + osDir + "/" + libName)) {
            if (is == null) throw new IOException("Profiler library not found for " + osDir);
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        targetPath.toFile().deleteOnExit();
        return targetPath.toAbsolutePath().toString();
    }

    public sealed static interface OrderBookCommand {
        void execute(OrderBook book);

        record Add(long orderId, Order.Side side, long price, long quantity) implements OrderBookCommand {
            @Override
            public void execute(OrderBook book) {
                book.addOrder(orderId, side, price, quantity);
            }
        }

        record Cancel(long orderId) implements OrderBookCommand {
            @Override
            public void execute(OrderBook book) {
                book.cancelOrder(orderId);
            }
        }

        record Modify(long orderId, long newPrice, long newQuantity) implements OrderBookCommand {
            @Override
            public void execute(OrderBook book) {
                book.modifyOrder(orderId, newPrice, newQuantity);
            }
        }
    }
}
