# Autonomous Micro-Optimization Framework

This document defines the process for autonomous performance optimization of the `OrderBook` implementation.

## Setup
To set up a new experiment, work with the user to:
1. **Agree on a run tag**: propose a tag based on today's date (e.g. `mar18`). The branch `microtune/<tag>` must not already exist — this is a fresh run.
2. **Create the branch**: `git checkout -b microtune/<tag>` from current master.
3. **Read the in-scope files**:
   - `src/main/java/com/xiaohanc/orderbook/OrderBookImpl.java` — the file you modify.
   - `src/main/java/com/xiaohanc/orderbook/Order.java` — (Optional) additional file for modification.
   - `src/test/java/com/xiaohanc/orderbook/OrderBookBenchmark.java` — fixed JMH benchmark. Do not modify.
   - `src/test/java/com/xiaohanc/orderbook/OrderBookTest.java` — correctness tests. Read-only.
4. **Initialize results.tsv**: Create `results.tsv` with just the header row: `commit	score	improvement	status	description`. The baseline will be recorded after the first run.
5. **Confirm and go**: Confirm setup looks good. Once you get confirmation, kick off the experimentation.

## Experimentation
Each experiment runs a JMH benchmark for a fixed number of iterations. You launch it simply as: `./gradlew benchmark -PrunArgs="-rf json -rff candidate.json -wi 5 -i 5"`.

**What you CAN do:**
- Modify `OrderBookImpl.java` — everything is fair game: data structures, algorithms, inlining, etc.
- You can add private helper methods or classes within the same package if needed.
## Optimization Methodology
The optimization process focuses on:
- **Branch Prediction**: Minimize unpredictable branches. Prefer branchless code (e.g., bitwise operations) in performance-critical paths.
- **Cache Locality**: Optimize for CPU caches. Use contiguous memory (arrays vs. linked structures), minimize pointer chasing, and maintain data proximity.
- **Complexity Analysis**: Balance performance gains against implementation complexity. Simpler code is preferred unless a significant performance benefit is demonstrated.

**What you CANNOT do:**
- Modify `OrderBookBenchmark.java`. It is read-only and contains the fixed evaluation harness.
- Modify `OrderBookTest.java` or other tests. Correctness must be preserved.
- Install new packages or add dependencies to `build.gradle.kts`.

The primary objective is to minimize **AverageTime**. Implementations must pass all functional tests and maintain system stability.

**Baseline**: The initial execution must establish a baseline performance metric: `./gradlew benchmark -PrunArgs="-rf json -rff baseline.json -wi 5 -i 5"`.

## Output format
JMH produces a JSON report (`candidate.json`). You can use the provided script to compare scores:
```bash
python3 compare_benchmarks.py baseline.json candidate.json replayOrders
```
The script will output:
```
Baseline:  <score> ± <error>
Candidate: <score> ± <error>
Improvement: <percent>%
RESULT: <BETTER/WORSE>
```

## Logging results
When an experiment is done, log it to `results.tsv` (tab-separated).
The TSV has a header row and 5 columns:
```
commit	score	improvement	status	description
```
1. git commit hash (short, 7 chars)
2. score (AverageTime in microseconds)
3. improvement (percentage relative to baseline)
4. status: `keep`, `discard`, or `crash`
5. short text description of what this experiment tried

Example:
```
commit	score	improvement	status	description
a1b2c3d	6160893.243	0.00%	keep	baseline
b2c3d4e	5950123.456	3.42%	keep	replace ArrayList with custom array
c3d4e5f	6200000.000	-0.64%	discard	use TreeMap for bids/asks
```

## The experiment loop
The experiment runs on a dedicated branch (e.g. `microtune/mar17`).
## Autonomous Execution Loop
1. Observe the current git state (branch and commit).
2. Implement a micro-optimization in `OrderBookImpl.java`.
3. Execute functional tests: `./gradlew test`.
4. If tests pass, commit the changes.
5. Execute the benchmark: `./gradlew benchmark -PrunArgs="-rf json -rff candidate.json -wi 5 -i 5"`.
6. Analyze results: `python3 compare_benchmarks.py baseline.json candidate.json replayOrders`.
7. Record the performance data in `results.tsv` (untracked by git).
8. If the candidate is BETTER, advance the branch by amending the commit message with the score and improvement percentage.
   - `git commit --amend -m "$(git log -1 --pretty=%B) (Score: <score>, Improvement: <improvement>%)"`
   - Update the baseline: `mv candidate.json baseline.json`
9. If the candidate is WORSE, revert the changes (`git reset --hard HEAD~1`).
10. If the build or tests fail, document the failure in `results.tsv`, revert, and proceed to the next iteration.

The agent operates as an autonomous researcher. Successful iterations are integrated into the optimization branch, while unsuccessful ones are discarded. 

**Error Handling**: If an iteration fails (e.g., due to a compilation error or test failure), evaluate the cause. Minor issues may be corrected; otherwise, the iteration should be logged as a failure and discarded.

**Continuous Operation**: The execution loop should continue until manually terminated. The agent must proceed independently with new optimization hypotheses, leveraging performance profiles (e.g., `profiler-results/summary-cpu.txt`) to identify further opportunities.
