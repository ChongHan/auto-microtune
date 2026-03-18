import json
import sys

def get_stats(filepath, benchmark_name):
    try:
        with open(filepath, 'r') as f:
            for b in json.load(f):
                if benchmark_name in b['benchmark']:
                    m = b['primaryMetric']
                    return m['score'], (0 if str(m.get('scoreError')) == 'NaN' else m.get('scoreError', 0))
    except Exception: pass
    return None, None

def main():
    if len(sys.argv) < 4:
        print("Usage: python3 compare_benchmarks.py <baseline.json> <candidate.json> <benchmark_name>")
        sys.exit(1)

    b_s, b_e = get_stats(sys.argv[1], sys.argv[3])
    c_s, c_e = get_stats(sys.argv[2], sys.argv[3])

    if b_s is None or c_s is None:
        print("Error: Could not find benchmark scores.")
        sys.exit(1)

    print(f"Baseline:  {b_s:.4f} ± {b_e:.4f}")
    print(f"Candidate: {c_s:.4f} ± {c_e:.4f}")
    print(f"Improvement: {(b_s - c_s) / b_s * 100:.2f}%")

    # To be conservative, candidate is BETTER if it's strictly better than baseline
    # even when considering the candidate's error margin.
    # JMH's scoreError is a confidence interval radius.
    # We consider it BETTER if candidate score plus error is less than baseline 
    # and candidate score is outside baseline error margin.
    # We consider it WORSE if candidate score is greater than baseline plus baseline error.
    # Otherwise, it's `SIMILAR` (within error margin).
    if (c_s + c_e) < b_s and c_s < (b_s - b_e):
        result = "BETTER"
        exit_code = 0
    elif c_s > (b_s + b_e):
        result = "WORSE"
        exit_code = 1
    else:
        result = "SIMILAR"
        exit_code = 2

    print(f"RESULT: {result}")
    sys.exit(exit_code)

if __name__ == "__main__":
    main()
