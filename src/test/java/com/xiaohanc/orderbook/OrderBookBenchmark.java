package com.xiaohanc.orderbook;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.AffinityStrategies;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1)
public class OrderBookBenchmark {

    private List<OrderBookCommand> commands;
    private OrderBook orderBook;
    private AffinityLock affinityLock;

    @Setup(Level.Trial)
    public void setup() {
        affinityLock = AffinityLock.acquireLock(11);
        int numCommands = 1_000_000;
        commands = new ArrayList<>(numCommands);
        Random random = new Random(42);
        
        int adds = 0, modifies = 0, cancels = 0;
        class Counter { int count = 0; }
        Counter matchCounter = new Counter();
        OrderBook simBook = new OrderBookImpl((mId, tId, p, q) -> matchCounter.count++);
        
        Set<Long> usedOrderIds = new HashSet<>();
        
        int minPrice = 10_000 + random.nextInt(100_000);
        int maxPrice = minPrice + 100_000 + random.nextInt(900_000);
        double midPrice = (minPrice + maxPrice) / 2.0;
        double spread = (maxPrice - minPrice) * 0.1;

        for (int i = 0; i < numCommands; i++) {
            List<Order> all = new ArrayList<>(simBook.getBids());
            all.addAll(simBook.getAsks());

            double prob = random.nextDouble();
            OrderBookCommand cmd;
            if (prob < 0.75 || all.isEmpty()) {
                Order.Side side = random.nextBoolean() ? Order.Side.BUY : Order.Side.SELL;
                long price;
                if ((side == Order.Side.BUY)) {
                    price = generateNormalInt(random, minPrice, maxPrice , midPrice - spread, (maxPrice - minPrice) /2.6 );
                } else {
                    price = generateNormalInt(random, minPrice, maxPrice, midPrice + spread, (maxPrice - minPrice)/2.6 );
                }
                long orderId;
                do {
                    orderId = Math.abs(random.nextLong());
                } while (!usedOrderIds.add(orderId));
                
                cmd = new OrderBookCommand.Add(orderId, side, price, nextValue(random, 1, 100));
                adds++;
            } else if (prob < 0.85) {
                Order target = all.get(random.nextInt(all.size()));
                cmd = new OrderBookCommand.Modify(target.id(), nextValue(random, minPrice, maxPrice), nextValue(random, 1, 300));
                modifies++;
            } else {
                Order target = all.get(random.nextInt(all.size()));
                cmd = new OrderBookCommand.Cancel(target.id());
                cancels++;
            }
            commands.add(cmd);
            cmd.execute(simBook);
        }

//        System.out.printf("Generated %d commands: Adds=%d, Modifies=%d, Cancels=%d, Matches=%d, OrderBook-Size=%d/%d%n",
//            numCommands, adds, modifies, cancels, matchCounter.count,
//            simBook.getBids().size(), simBook.getAsks().size());
    }

    private long nextValue(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }


    private int generateNormalInt(Random random, int min, int max, double mean, double stdDev) {
        int result;
        do {
            double gaussian = random.nextGaussian();
            result = (int) Math.round(mean + gaussian * stdDev);
        } while (result < min || result > max);

        return result;
    }

    @Setup(Level.Invocation)
    public void iterationSetup() {
        orderBook = new OrderBookImpl((mId, tId, p, q) -> {});
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (affinityLock != null) {
            affinityLock.release();
        }
    }

    @Benchmark
    public void replayOrders() {
        for (OrderBookCommand cmd : commands) {
            cmd.execute(orderBook);
        }
    }

    public static void main(String[] args) throws Exception {
        String libPath = extractProfiler();

//        OrderBookBenchmark benchmark = new OrderBookBenchmark();
//        benchmark.setup();
//        benchmark.iterationSetup();

        Options commandLineOptions = new CommandLineOptions(args);
        
        String profilerOptions = "libPath=" + libPath + ";output=text;dir=profiler-results";
        
        Options options = new OptionsBuilder()
            .parent(commandLineOptions)
            .include(OrderBookBenchmark.class.getSimpleName())
            .addProfiler(AsyncProfiler.class, profilerOptions)
                .addProfiler("gc")
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
