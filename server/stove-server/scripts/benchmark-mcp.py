#!/usr/bin/env python3
"""Benchmark the real Stove MCP endpoint with isolated, synthetic failure evidence.

Requires protoc, protobuf and tiktoken. See acceptance-tests/README.md for usage.
No existing server or database is contacted. Latencies exclude tokenization.
"""
import argparse
import concurrent.futures
import datetime as dt
import hashlib
import http.client
import importlib
import json
import math
import os
from pathlib import Path
import platform
import socket
import statistics
import subprocess
import sys
import tempfile
import time

import tiktoken
from google.protobuf.json_format import ParseDict

ROOT = Path(__file__).resolve().parents[3]
SERVER = ROOT / "server/stove-server"
RUN = "benchmark-run"
STAMP = "2026-09-07T10:00:00Z"
SCENARIOS = {
    "assertion": (30, 0, "expected EUR 100.00, received EUR 99.00"),
    "database": (40, 80, "SQLSTATE 23505 duplicate key orders_pkey"),
    "mock": (40, 0, "header X-Tenant expected checkout, received catalog"),
    "long_trace": (250, 400, "DEADLINE_EXCEEDED inventory reservation timed out"),
    "wide_payload": (10, 0, "SKU-0999 unavailable in warehouse AMS"),
}


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class Client:
    def __init__(self, port):
        self.connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30)

    def close(self):
        self.connection.close()

    def request(self, method, path, body=None, content_type="application/json", expected_status=200):
        started = time.perf_counter_ns()
        self.connection.request(method, path, body, {
            "Content-Type": content_type,
            "Accept": "application/json, text/event-stream",
        })
        response = self.connection.getresponse()
        raw = response.read()
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        if response.status != expected_status:
            raise RuntimeError(f"HTTP {response.status}: {raw[:300]!r}")
        return raw, elapsed

    def rpc(self, method, params):
        raw, elapsed = self.request("POST", "/mcp", compact({
            "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
        }))
        return json.loads(raw), raw, elapsed

    def tool(self, name, arguments):
        return self.rpc("tools/call", {"name": name, "arguments": arguments})


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def proto_module(directory):
    source = ROOT / "lib/stove-dashboard-api/src/main/proto"
    subprocess.run(["protoc", f"-I{source}", f"--python_out={directory}",
                    str(source / "stove/dashboard/v1/dashboard_events.proto")], check=True)
    sys.path.insert(0, str(directory))
    return importlib.import_module("stove.dashboard.v1.dashboard_events_pb2")


def seed(client, proto):
    count = 0
    def emit(kind, value):
        nonlocal count
        count += 1
        event = ParseDict({"run_id": RUN, "event_id": f"event-{count}",
                           "sequence": count, kind: value}, proto.DashboardEvent())
        raw, _ = client.request("POST", "/api/v1/events", event.SerializeToString(),
                                "application/x-protobuf")
        ack = proto.EventAck.FromString(raw)
        if not ack.accepted or ack.duplicate:
            raise RuntimeError(f"ingestion rejected event {count}")

    emit("run_started", {"app_name": "mcp-benchmark", "timestamp": STAMP,
                         "metadata": {"team": "checkout", "pipeline": "benchmark"}})
    wide = {f"sku_{i:04}": {"sku": f"SKU-{i:04}", "stock": i % 20,
            "description": "Synthetic inventory record for an order awaiting warehouse allocation. " * 3}
            for i in range(1200)}
    wide["sku_0999"]["diagnosis"] = SCENARIOS["wide_payload"][2]
    for scenario, (entries, spans, cause) in SCENARIOS.items():
        emit("test_started", {"test_id": scenario, "test_name": scenario,
                              "spec_name": "BenchmarkSpec", "timestamp": STAMP})
        for i in range(entries):
            failed = i == entries - 1
            payload = {"order": i, "authorization": "synthetic-secret",
                       "description": "Order event being processed by checkout. " * 40}
            if scenario == "wide_payload" and failed:
                payload = {"catalog": wide}
            emit("entry_recorded", {
                "test_id": scenario, "timestamp": STAMP, "system": "HTTP",
                "action": f"processing step {i}", "result": "FAILED" if failed else "PASSED",
                "input": compact(payload), "output": compact({"accepted": not failed}),
                "expected": "processing complete" if failed else "",
                "actual": cause if failed and scenario == "assertion" else "",
                "error": "Processing did not complete" if failed else "",
                "trace_id": f"trace-{scenario}" if spans else "",
            })
        for i in range(spans):
            failed = i == spans - 1
            event = {"trace_id": f"trace-{scenario}", "span_id": f"span-{i}",
                     "parent_span_id": f"span-{i-1}" if i else "",
                     "operation_name": f"service.operation.{i}", "service_name": "checkout",
                     "start_time_nanos": 1_000_000_000 + i * 1000,
                     "end_time_nanos": 2_000_000_000 - i * 1000,
                     "status": "ERROR" if failed else "OK",
                     "attributes": {"x-stove-test-id": scenario}}
            if failed:
                event["exception"] = {"type": "DatabaseException" if scenario == "database" else "TimeoutException",
                    "message": cause, "stack_trace": [f"checkout.Service.operation{i}(Service.kt:{i})" for i in range(300)]}
            emit("span_recorded", event)
        if scenario == "mock":
            for i in range(25):
                emit("mock_interaction", {"test_id": scenario, "timestamp": STAMP,
                    "system": "WireMock", "protocol": "HTTP", "method": "POST", "target": "/reserve",
                    "matched": False, "attribution": "PROVEN_HEADER", "status": "404",
                    "latency_ms": 5, "request_body": compact({"tenant": "catalog", "order": i}),
                    "near_misses": [cause], "configured_delay_ms": -1, "client_deadline_ms": -1})
        if scenario == "wide_payload":
            emit("snapshot", {"test_id": scenario, "timestamp": STAMP, "system": "Kafka",
                "state_json": compact({"catalog": wide, "published": [{"order": i} for i in range(100)]}),
                "summary": "Inventory catalogue and published events at failure", "trigger": "FAILURE"})
        emit("test_ended", {"test_id": scenario, "timestamp": STAMP, "status": "FAILED",
                            "duration_ms": 5000, "error": "Processing did not complete"})
    emit("run_ended", {"timestamp": STAMP, "total_tests": len(SCENARIOS),
                       "failed": len(SCENARIOS), "duration_ms": 30000})
    return count


def percentile(values, quantile):
    return sorted(values)[max(0, math.ceil(len(values) * quantile) - 1)]


def summary(latencies):
    return {"samples": len(latencies), "p50_ms": round(statistics.median(latencies), 3),
            "p95_ms": round(percentile(latencies, .95), 3),
            "p99_ms": round(percentile(latencies, .99), 3), "max_ms": round(max(latencies), 3)}


def queries():
    for scenario in SCENARIOS:
        for budget in ("tiny", "compact", "full"):
            yield f"diagnose/{scenario}/{budget}", "stove_diagnose", {
                "run_id": RUN, "test_id": scenario, "budget": budget}, "tool"
    yield "diagnose/run", "stove_diagnose", {"run_id": RUN, "limit": 5}, "tool"
    yield "diagnose/metadata", "stove_diagnose", {"app_name": "mcp-benchmark",
        "metadata": {"team": "checkout", "pipeline": "benchmark"}, "limit": 5}, "tool"
    yield "discovery", "tools/list", {}, "rpc"
    yield "survey", "stove_failures", {"run_id": RUN, "limit": 5, "budget": "tiny"}, "tool"
    for scenario in SCENARIOS:
        for budget in ("tiny", "compact", "full"):
            yield f"detail/{scenario}/{budget}", "stove_failure_detail", {
                "run_id": RUN, "test_id": scenario, "budget": budget}, "tool"
    for view in ("critical_path", "exceptions", "tree"):
        for budget in ("tiny", "compact", "full"):
            yield f"trace/{view}/{budget}", "stove_trace", {
                "run_id": RUN, "test_id": "long_trace", "view": view, "budget": budget}, "tool"
    for pointer in (None, "/catalog/sku_0999", "/catalog/sku_0999/diagnosis", "/published/75"):
        arguments = {"run_id": RUN, "test_id": "wide_payload", "budget": "tiny"}
        if pointer is not None:
            arguments["json_pointer"] = pointer
        yield f"snapshot/{pointer or 'all'}", "stove_snapshot", arguments, "tool"
    yield "snapshot/record/compact", "stove_snapshot", {
        "run_id": RUN, "test_id": "wide_payload", "budget": "compact",
        "json_pointer": "/catalog/sku_0999"}, "tool"
    for scenario in ("database", "long_trace"):
        yield f"timeline/{scenario}", "stove_timeline", {
            "run_id": RUN, "test_id": scenario, "budget": "compact"}, "tool"
    yield "interactions/mock", "stove_interactions", {
        "run_id": RUN, "test_id": "mock", "budget": "compact"}, "tool"
    yield "invalid_filter", "stove_runs", {"metdata": {"team": "checkout"}}, "error"


def measure(client, query, samples, encoding, output):
    label, tool, arguments, kind = query
    call = (lambda: client.rpc(tool, arguments)) if kind == "rpc" else (lambda: client.tool(tool, arguments))
    for _ in range(3):
        call()
    times = []
    for _ in range(samples):
        response, raw, elapsed = call()
        if "error" in response or response["result"].get("isError", False) != (kind == "error"):
            raise RuntimeError(f"unexpected tool result for {label}: {str(response)[:500]}")
        times.append(elapsed)
    result = response["result"]
    structured = result.get("structuredContent", result)
    text = result.get("content", [{}])[0].get("text", compact(structured))
    if "structuredContent" in result and json.loads(text) != structured:
        raise RuntimeError(f"text and structured evidence differ: {label}")
    if "synthetic-secret" in text:
        raise RuntimeError(f"redaction failed: {label}")
    tokens = lambda value: len(encoding.encode(value, disallowed_special=()))
    row = {"case": label, "tool": tool, "arguments": arguments, **summary(times),
           "wire_bytes": len(raw), "text_bytes": len(text.encode()), "text_tokens": tokens(text),
           "pretty_text_tokens": tokens(json.dumps(structured, ensure_ascii=False, indent=2)),
           "wire_tokens": tokens(raw.decode()), "latencies_ms": times}
    if label.startswith("detail/"):
        row["cause_present"] = SCENARIOS[arguments["test_id"]][2] in text
    if label.startswith("diagnose/"):
        scenarios = [arguments["test_id"]] if "test_id" in arguments else list(SCENARIOS)
        row["causes_present"] = {scenario: SCENARIOS[scenario][2] in text for scenario in scenarios}
        if not all(row["causes_present"].values()):
            raise RuntimeError(f"diagnosis omitted a seeded cause: {label}: {row['causes_present']}")
        context_checks = {}
        for diagnosis in structured["diagnoses"]:
            scenario = diagnosis["test"]["test_id"]
            entry_count, span_count, _ = SCENARIOS[scenario]
            timeline, trace = diagnosis["timeline_summary"], diagnosis["trace_summary"]
            events, path = timeline["events"], trace.get("critical_path", [])
            valid = (len(events) <= 5
                     and any(event["action"] == f"processing step {entry_count - 1}"
                             and event["result"] == "FAILED" for event in events)
                     and timeline["omitted_events"] == entry_count - len(events)
                     and len(path) <= 8
                     and trace["omitted_spans"] == span_count - len(path))
            if span_count:
                valid = valid and path[-1]["span_id"] == f"span-{span_count - 1}" and path[-1]["status"] == "ERROR"
            else:
                valid = valid and trace["trace_status"] == "uncorrelated"
            context_checks[scenario] = valid
        row["context_checks"] = context_checks
        if not all(context_checks.values()):
            raise RuntimeError(f"diagnosis lost failure context: {label}: {context_checks}")
    if label.startswith("snapshot/") and "json_pointer" in arguments:
        row["pointer_status"] = structured["snapshots"][0]["state"]["parse_status"]
        row["selected_value"] = structured["snapshots"][0]["state"].get("value")
        row["cause_present"] = SCENARIOS["wide_payload"][2] in text
    if label.startswith("trace/"):
        row["cause_present"] = SCENARIOS["long_trace"][2] in text
    (output / (label.replace("/", "_") + ".json")).write_text(compact(response))
    print(f"{label:40} p95={row['p95_ms']:8.2f}ms text={row['text_tokens']:7} tokens", flush=True)
    return row


def concurrent_load(port, cases, requests, concurrency):
    def worker(index):
        client = Client(port)
        measurements = []
        try:
            for number in range(index, requests, concurrency):
                label, name, arguments, _ = cases[number % len(cases)]
                response, _, elapsed = client.tool(name, arguments)
                if response.get("error") or response["result"].get("isError"):
                    raise RuntimeError(f"concurrent request failed: {label}")
                measurements.append(elapsed)
        finally:
            client.close()
        return measurements
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        measurements = [item for batch in pool.map(worker, range(concurrency)) for item in batch]
    elapsed = time.perf_counter() - started
    return {**summary(measurements), "concurrency": concurrency,
            "requests_per_second": round(requests / elapsed, 1), "cases": [c[0] for c in cases]}


def raw_baseline(client, scenario, encoding):
    paths = [f"/runs/{RUN}/tests/{scenario}/{kind}" for kind in
             ("entries/raw", "spans", "snapshots", "mock-interactions")]
    values = []
    for path in paths:
        raw, _ = client.request("GET", "/api/v1" + path)
        values.append(json.loads(raw))
    text = compact(values)
    return {"scenario": scenario, "text_tokens": len(encoding.encode(text, disallowed_special=())),
            "text_bytes": len(text.encode()), "requests": len(paths)}


def error_probes(client):
    cases = [
        ("unknown_argument", "stove_runs", {"metdata": {}}, "metdata"),
        ("invalid_budget", "stove_failures", {"budget": "small"}, "budget"),
        ("invalid_limit", "stove_runs", {"limit": 0}, "limit"),
        ("invalid_view", "stove_trace", {"trace_id": "trace-long_trace", "view": "flat"}, "view"),
        ("missing_selector", "stove_failure_detail", {}, "run_id"),
        ("missing_run", "stove_failure_detail", {"run_id": "absent", "test_id": "absent"}, "absent"),
    ]
    results = []
    for label, tool, arguments, expected in cases:
        response, _, elapsed = client.tool(tool, arguments)
        result = response.get("result", {})
        message = result.get("content", [{}])[0].get("text", "")
        passed = result.get("isError") is True and expected in message
        results.append({"case": label, "passed": passed, "latency_ms": elapsed, "message": message})
    for label, params in (("unknown_tool", {"name": "stove_absent"}), ("malformed_call", {"arguments": {}})):
        response, _, elapsed = client.rpc("tools/call", params)
        results.append({"case": label, "passed": response.get("error", {}).get("code") == -32602,
                        "latency_ms": elapsed, "response": response})
    raw, elapsed = client.request("POST", "/mcp", "{bad", expected_status=400)
    response = json.loads(raw)
    results.append({"case": "malformed_json", "passed": response.get("error", {}).get("code") == -32700,
                    "latency_ms": elapsed, "response": response})
    if not all(result["passed"] for result in results):
        raise RuntimeError(f"error handling failed: {results}")
    return results


def diagnosis_loop(client, encoding):
    arguments = {"app_name": "mcp-benchmark", "metadata": {"pipeline": "benchmark"}, "limit": 2}
    seen, causes, times, tokens = set(), {}, [], 0
    for _ in range(len(SCENARIOS)):
        response, _, elapsed = client.tool("stove_diagnose", arguments)
        if response.get("error") or response["result"].get("isError"):
            raise RuntimeError(f"diagnosis loop failed: {response}")
        result = response["result"]
        content = result["structuredContent"]
        if content["run_id"] != RUN:
            raise RuntimeError("diagnosis loop changed run")
        tokens += len(encoding.encode(result["content"][0]["text"], disallowed_special=()))
        times.append(elapsed)
        for diagnosis in content["diagnoses"]:
            scenario = diagnosis["test"]["test_id"]
            if scenario in seen:
                raise RuntimeError(f"diagnosis loop repeated {scenario}")
            seen.add(scenario)
            causes[scenario] = SCENARIOS[scenario][2] in compact(diagnosis)
        next_call = content["next_tool_call"]
        if next_call is None:
            break
        if next_call["tool"] != "stove_diagnose" or next_call["arguments"]["run_id"] != RUN:
            raise RuntimeError("unexpected diagnosis continuation")
        arguments = next_call["arguments"]
    if seen != set(SCENARIOS) or not all(causes.values()) or next_call is not None:
        raise RuntimeError(f"incomplete diagnosis loop: {causes}")
    return {"requests": len(times), "tests": len(seen), "causes_present": causes,
            "total_text_tokens": tokens, "latencies_ms": times}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=SERVER / "target/release/stove")
    parser.add_argument("--output", type=Path, default=SERVER / "target/mcp-benchmark")
    parser.add_argument("--samples", type=int, default=60)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--requests", type=int, default=240)
    parser.add_argument("--suite", choices=("full", "diagnosis"), default="full")
    args = parser.parse_args()
    if min(args.samples, args.concurrency, args.requests) < 1:
        parser.error("counts must be positive")
    binary = args.binary.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    encoding = tiktoken.get_encoding("o200k_base")
    with tempfile.TemporaryDirectory(prefix="stove-mcp-benchmark-") as temporary:
        directory = Path(temporary)
        proto = proto_module(directory)
        port, grpc_port = free_port(), free_port()
        while grpc_port == port:
            grpc_port = free_port()
        # Never inherit a user's server/database configuration into the fixture.
        child_env = {key: value for key, value in os.environ.items() if not key.startswith("STOVE_")}
        with (output / "server.log").open("w") as log:
            process = subprocess.Popen([str(binary), "--port", str(port), "--grpc-port", str(grpc_port),
                "--db", str(directory / "benchmark.sqlite"), "--retention-runs-per-app", "0", "--no-skills-check"],
                stdout=log, stderr=log, env={**child_env, "RUST_LOG": "warn"})
            client = Client(port)
            try:
                deadline = time.monotonic() + 30
                while True:
                    try:
                        client.request("GET", "/api/v1/meta")
                        break
                    except (OSError, http.client.HTTPException):
                        client.close()
                        client = Client(port)
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError(f"server did not start; see {output / 'server.log'}")
                        time.sleep(.05)
                events = seed(client, proto)
                print(f"Seeded {events} events through the protobuf HTTP endpoint", flush=True)
                initialized, _, _ = client.rpc("initialize", {"protocolVersion": "2025-06-18",
                    "capabilities": {}, "clientInfo": {"name": "stove-benchmark", "version": "1"}})
                if initialized.get("error"):
                    raise RuntimeError(f"initialize failed: {initialized}")
                errors = error_probes(client)
                loop = diagnosis_loop(client, encoding)
                cases = list(queries())
                if args.suite == "diagnosis":
                    cases = [case for case in cases if case[0].startswith("diagnose/")]
                rows = [measure(client, case, args.samples, encoding, output) for case in cases]
                prefix = "diagnose" if args.suite == "diagnosis" else "detail"
                normal = [case for case in cases if case[0].startswith(prefix + "/") and case[0].endswith("/compact") and "wide_payload" not in case[0]]
                mixed = normal + [case for case in cases if case[0] == f"{prefix}/wide_payload/compact"]
                loads = {"ordinary": concurrent_load(port, normal, args.requests, args.concurrency),
                         "with_wide_payload": concurrent_load(port, mixed, args.requests, args.concurrency)}
                baselines = [raw_baseline(client, scenario, encoding) for scenario in SCENARIOS]
                report = {"created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "suite": args.suite,
                    "platform": platform.platform(), "machine": platform.machine(), "cpu": subprocess.check_output(["sysctl", "-n", "machdep.cpu.brand_string"], text=True).strip() if sys.platform == "darwin" else platform.processor(),
                    "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                    "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
                    "tokenizer": "o200k_base", "tiktoken_version": tiktoken.__version__,
                    "method": "loopback HTTP/1.1, persistent connections, temporary SQLite, 3 warmups per case; RTT ends after full body read, excludes JSON parsing/tokenization; synthetic fixtures; not an LLM diagnosis evaluation",
                    "events": events, "scenarios": SCENARIOS, "measurements": rows,
                    "concurrent": loads, "raw_rest_baselines": baselines, "error_probes": errors,
                    "diagnosis_loop": loop}
                (output / "results.json").write_text(json.dumps(report, indent=2))
                print(json.dumps(loads, indent=2), flush=True)
                print(f"Results: {output / 'results.json'}", flush=True)
            finally:
                client.close()
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
