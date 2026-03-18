# auto-microtune

`auto-microtune` is an experiment for autonomous micro-optimization of performance-critical Java code. It uses an AI agent to iteratively refine an implementation, guided by JMH benchmarks and automated correctness verification. Inspired by https://github.com/karpathy/autoresearch/tree/master

The idea: give an AI agent a performance-critical piece of code (like an `OrderBook`) and a fixed JMH benchmark, then let it experiment autonomously. It modifies the code, runs the benchmark, checks if the performance improved, keeps or discards, and repeats. The agent is essentially a "researcher" that optimizes data structures, algorithms, and low-level code patterns (branchless logic, cache locality, etc.) to achieve the lowest possible latency.

## How it works

The repository is built around the "experiment loop" defined in `program.md`. There are four key components:

- **`OrderBookImpl.java`** — the primary file the agent edits. Everything inside is fair game for micro-optimization: bit-fiddling, custom data structures, inlining, etc.
- **`OrderBookBenchmark.java`** — the fixed JMH benchmark harness. Not modified by the agent to ensure a stable, fair evaluation of every candidate.
- **`compare_benchmarks.py`** — a utility script that compares the JMH results of a `candidate` against a `baseline`, accounting for measurement error to ensure improvements are statistically significant.
- **`program.md`** — the execution plan. It contains the instructions, tips, and the loop logic that the AI agent follows to perform its work.

The metric is **AverageTime** in microseconds (lower is better). Each experiment is logged to `results.tsv` to track the history of the optimization process.

## Sample Run

Branch `microtune/mar22` is a complete sample run of the loop: the branch-local [experiment summary](https://github.com/ChongHan/auto-microtune/blob/microtune/mar22/EXPERIMENT_SUMMARY.md) shows why alternating broad `tick` changes with local `tock` refinements works, because the big wins came from architecture changes while the last miles came from small map and bookkeeping improvements. It also shows the main risk of this style of optimization: the agent will learn the benchmark's exact workload and dataset shape, so the benchmark must be representative and the tests must be strong enough to reject “fast but wrong” designs.

That run converged on an `OrderBook` design where orders live in primitive slot arrays, each price level is an intrusive FIFO queue, order lookup uses a specialized open-addressed intrusive map, and best-price selection uses a dense rebasing page directory with 64 prices per page tracked by a bitset. For example, a buy order at price `10_125` maps to page `10_125 >> 6` and bit offset `10_125 & 63`; finding the best bid means taking the highest non-empty page and then the highest active bit inside that page, while cancels and modifies still find the resting order directly by id through the intrusive map instead of scanning levels.

## Quick start

**Requirements:** Java 21+, Gradle.

```bash
# 1. Run tests to ensure baseline correctness
./gradlew test

# 2. Establish the baseline benchmark (takes a few minutes)
./gradlew benchmark -PrunArgs="-rf json -rff baseline.json -wi 5 -i 5"

# 3. Compare (will just show the baseline for now)
python3 compare_benchmarks.py baseline.json baseline.json replayOrders
```

If the above commands work, you're ready to start the autonomous tuning.

## Running the agent

Point your favorite AI agent (Claude, Codex, etc.) at this repository and give it a prompt like:

```
Hi have a look at program.md and let's kick off a new experiment! let's do the setup first.
```

The agent will then take over: coming up with an idea, implementing it, verifying correctness, benchmarking it, and deciding whether to keep it based on the results.
