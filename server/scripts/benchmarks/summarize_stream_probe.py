"""Summarize measured streaming events; optional CER needs an exact reference."""
import argparse
import json
import math
from pathlib import Path
import re
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path)
    parser.add_argument("--reference", type=Path)
    args = parser.parse_args()
    events = [json.loads(line) for line in args.events.read_text(encoding="utf-8").splitlines()]
    previews = [e for e in events if e["type"] == "preview"]
    summary = next(e for e in reversed(events) if e["type"] == "summary")
    lag = sorted(e["client_delay_ms"] for e in previews)
    intervals = [b["client_received_ms"] - a["client_received_ms"] for a, b in zip(previews, previews[1:])]
    result = {
        "ready": next(e for e in events if e["type"] == "ready"),
        "summary": summary,
        "first_client_partial_ms": previews[0]["client_received_ms"],
        "preview_count": len(previews),
        "update_interval_median_ms": statistics.median(intervals) if intervals else None,
        "client_delay_median_ms": statistics.median(lag),
        "client_delay_p95_ms": lag[math.ceil(len(lag) * .95) - 1],
        "client_delay_max_ms": max(lag),
        "last_preview_delay_ms": previews[-1]["client_delay_ms"],
    }
    if args.reference:
        def normalize(value):
            return re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", value).lower()
        reference = normalize(args.reference.read_text(encoding="utf-8"))
        hypothesis = normalize(summary["final_text"])
        if not reference:
            raise ValueError("Reference has no normalized characters")
        previous = list(range(len(hypothesis) + 1))
        for i, a in enumerate(reference, 1):
            row = [i]
            for j, b in enumerate(hypothesis, 1):
                row.append(min(row[-1] + 1, previous[j] + 1, previous[j-1] + (a != b)))
            previous = row
        result["cer"] = {"edit_distance": previous[-1], "reference_characters": len(reference),
                         "rate": previous[-1] / len(reference),
                         "normalization": "Chinese/ASCII alphanumeric only, lowercase"}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
