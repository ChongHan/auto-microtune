# Autonomous Micro-Benchmark-Optimization Framework

This document defines the process for autonomous performance optimization of the `OrderBook` implementation.

## Setup
To set up a new experiment, work with the user to:
1. **Agree on a run tag**: propose a tag based on today's date (e.g. `mar18`). The branch `microtune/<tag>` must not already exist — this is a fresh run.
2. **Create the branch**: `git checkout -b microtune/<tag>` from current master.
3. **Read the in-scope files**:
   - `src/main/java/com/xiaohanc/orderbook/OrderBookImpl.java` — the file you modify.
   - `src/main/java/com/xiaohanc/orderbook/Order.java`
   - `src/test/java/com/xiaohanc/orderbook/OrderBookBenchmark.java` — fixed JMH benchmark. Do not modify.
   - `src/test/java/com/xiaohanc/orderbook/OrderBookTest.java` — correctness tests. Read-only.
4. **Initialize results.tsv**: Create `results.tsv` with just the header row: `commit	cycle	score	improvement	status	description`.
5. **Establish Baseline**: Run the initial benchmark to establish a baseline: `./gradlew benchmark -PrunArgs="-rf json -rff baseline.json -wi 3 -i 5" > benchmark.log 2>&1`. Record the result in `results.tsv` with cycle as `baseline`.
6. **Confirm and go**: Confirm setup looks good (check `benchmark.log` for any errors). Once you get confirmation, kick off the experimentation.

**What you CAN do:**
- Modify `OrderBookImpl.java` — everything is fair game: data structures, algorithms, vector API, etc.
- You can add private helper methods or classes within the same package if needed.

**What you CANNOT do:**
- Modify `OrderBookBenchmark.java`. It is read-only and contains the fixed evaluation harness.
- Modify `OrderBookTest.java` or other tests. Correctness must be preserved.
- Optimize specifically against benchmark. Game on benchmark data.

The primary objective is to minimize **AverageTime**. Implementations must pass all functional tests and maintain system stability.

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
RESULT: <BETTER/WORSE/SIMILAR>
```

## Logging results
When an experiment is done, log it to `results.tsv` (tab-separated).
The TSV has a header row and 6 columns:
```
commit	cycle	score	improvement	status	description
```
1. git commit hash (short, 7 chars)
2. cycle: `tick` (Strategic exploration), `tock` (Tactical refinement) or `baseline`
3. score (AverageTime in microseconds)
4. improvement (percentage relative to baseline)
5. status: `keep`, `discard`
6. short text description of what this experiment tried.

Example:
```
commit	cycle	score	improvement	status	description
a1b2c3d	baseline	6160893.243	0.00%	keep	baseline
b2c3d4e	tick	5950123.456	3.42%	keep	replace LinkedList with prmitive array
c3d4e5f	tock	6200000.000	-0.64%	discard	use TreeMap for bids/asks because it is not cache friendly
1111111	tick	6300000.000	-1.64%	discard	use primitive hashmap, worse result due to bad map implementation, worth exploring further
```

## The experiment loop
The experiment runs on a dedicated branch (e.g. `microtune/mar17`).

### LOOP
1. **Plan Current Iteration**:
   - Look at the git state: the current branch/commit we're on and the history of changes.
   - **Identify Cycle Type**: Alternate between 1 **Tick** and 1 **Tock**.
   - **Tick (Strategic Exploration)**: Break free from the current design. Propose a fundamental shift in data structures or algorithms. There are always large gains to be had.
   - **Tock (Tactical Refinement)**: Squeeze the performance out of the CURRENT architecture and refactor the code.
   - NEVER optimise specifically on benchmark setup data, price/quantity/type distributions are dynamic in the real world and can't be predicted. 
2. **Implement**: implement the proposed change.
3. **Test**: `./gradlew --stop > /dev/null 2>&1 && ./gradlew test > test.log 2>&1 && ./gradlew benchmark -PrunArgs="-rf json -rff candidate.json -wi 3 -i 5" > benchmark.log 2>&1 && python3 compare_benchmarks.py baseline.json candidate.json replayOrders`
4. **Log Results**: Record the performance data and cycle type in `results.tsv`. If `discard` include a short summary of why. (NOTE: do not commit the `results.tsv` file).
5. **Commit**: git commit regardless of the outcome.
6. **Handle Results**:
   - If the candidate is **BETTER**:
     - Update the baseline: `mv candidate.json baseline.json`
   - If the candidate is **WORSE**:
     - Discard changes: `git revert HEAD --no-edit && rm -rf profiler-results test.log benchmark.log`. 
   - If the candidate is **SIMILAR**:
     - Decide based on code complexity or other factors:
       - If keeping: Keep the commit and update the baseline: `mv candidate.json baseline.json`.
       - If discarding: Discard changes: `git revert HEAD --no-edit && rm -rf profiler-results test.log`. 
7. **Repeat**: back to step 1.

**Error Handling**: If an iteration fails (e.g., due to a compilation error or test failure), evaluate the cause. Minor issues may be corrected; otherwise, the iteration should be logged as a failure and discarded.
