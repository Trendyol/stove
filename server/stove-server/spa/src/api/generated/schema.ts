// Generated from the Rust OpenAPI contract. Run npm run generate:api; do not edit.

export interface paths {
    "/api/v1/admin/database/query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["execute_database_query"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/database/schema": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_database_schema"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/idle-timeout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["update_idle_timeout"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/purge": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["purge_runs"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/purge/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["preview_purge"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/retention": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["update_retention"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_admin_status"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/top-filter": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["update_top_filter"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agent-setup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_agent_setup"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/apps": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_apps"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/data": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["clear_all"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** `POST /api/v1/events` — ingest a single protobuf-encoded `DashboardEvent`. */
        post: operations["post_event"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/events/stream": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * SSE endpoint that streams dashboard events to connected browser clients.
         * @description Sends a keep-alive comment every 15 seconds to prevent proxies and browsers
         *     from closing the connection during long-running tests.
         */
        get: operations["sse_handler"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_health"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/meta": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_meta"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_runs"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_run"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/evidence/{kind}/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_run_focused_evidence"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/mock-interactions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_run_mock_interactions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/mock-interactions/ambient": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_unattributed_run_mock_interactions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/mock-warnings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_run_mock_warnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/mock-warnings/ambient": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_unattributed_run_mock_warnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/profiles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_profiles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_tests"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_test"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/entries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_entries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/entries/raw": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_raw_entries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/evidence/{kind}/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_focused_evidence"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/mock-interactions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_test_mock_interactions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/mock-warnings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_test_mock_warnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/snapshots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_snapshots"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{run_id}/tests/{test_id}/spans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_test_spans"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/top-filter": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_top_filter"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/traces/{trace_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_trace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/metrics": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Prometheus exposition; cached application/database values plus Linux process statistics.
         *     Deliberately performs no database calls.
         */
        get: operations["get_metrics"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AgentSetupProvider: {
            global: components["schemas"]["AgentSetupScope"];
            id: string;
            install_link?: string | null;
            label: string;
            note: string;
            project: components["schemas"]["AgentSetupScope"];
        };
        AgentSetupResponse: {
            endpoint: string;
            providers: components["schemas"]["AgentSetupProvider"][];
        };
        AgentSetupScope: {
            command: string;
            config_path: string;
            configuration: string;
            skill_path: string;
        };
        /** @description Summary of an application known to the dashboard. */
        AppSummary: {
            app_name: string;
            latest_run_id: string;
            latest_run_started_at: string;
            latest_status: components["schemas"]["RunStatus"];
            metadata: {
                [key: string]: string;
            };
            stove_version: string | null;
        };
        DatabaseColumn: {
            data_type: string;
            name: string;
            nullable: boolean;
            primary_key: boolean;
        };
        DatabaseQueryRequest: {
            max_rows?: number;
            sql: string;
        };
        DatabaseQueryResult: {
            /** Format: int64 */
            affected_rows: number;
            columns: string[];
            rows: (string | null)[][];
            truncated: boolean;
        };
        DatabaseSchema: {
            backend: string;
            tables: components["schemas"]["DatabaseTable"][];
        };
        DatabaseTable: {
            columns: components["schemas"]["DatabaseColumn"][];
            name: string;
        };
        /** @description A report entry (action + result) within a test. */
        Entry: {
            action: string;
            actual: string | null;
            /** @description Best-effort identity shared by repeated invocations of the same assertion. */
            assertion_id: string;
            /** Format: int64 */
            attempt_count: number;
            error: string | null;
            expected: string | null;
            /** Format: int64 */
            failure_count: number;
            /** Format: int64 */
            id: number;
            input: string | null;
            metadata: string | null;
            output: string | null;
            result: components["schemas"]["TestStatus"];
            run_id: string;
            system: string;
            test_id: string;
            timestamp: string;
            trace_id: string | null;
        };
        EvidenceCounts: {
            /** Format: int64 */
            entries: number;
            /** Format: int64 */
            mock_interactions: number;
            /** Format: int64 */
            mock_warnings: number;
            /** Format: int64 */
            snapshots: number;
            /** Format: int64 */
            spans: number;
            /** Format: int64 */
            tests: number;
        };
        /** @enum {string} */
        EvidenceKind: "entry" | "span" | "snapshot" | "interaction" | "warning";
        EvidenceTarget: {
            /** @enum {string} */
            kind: "entry";
            value: components["schemas"]["Entry"];
        } | {
            /** @enum {string} */
            kind: "span";
            value: components["schemas"]["Span"];
        } | {
            /** @enum {string} */
            kind: "snapshot";
            value: components["schemas"]["Snapshot"];
        } | {
            /** @enum {string} */
            kind: "interaction";
            value: components["schemas"]["MockInteraction"];
        } | {
            /** @enum {string} */
            kind: "warning";
            value: components["schemas"]["MockWarning"];
        };
        Finding: {
            evidence: string;
            kind: string;
            suggestion: string;
        };
        FocusedEvidence: {
            context_limit: number;
            entries: components["schemas"]["Entry"][];
            has_more_after: boolean;
            has_more_before: boolean;
            interactions: components["schemas"]["MockInteraction"][];
            spans: components["schemas"]["Span"][];
            target: components["schemas"]["EvidenceTarget"];
            warnings: components["schemas"]["MockWarning"][];
        };
        Frame: {
            file: string;
            /** Format: int64 */
            line: number;
            name: string;
        };
        HealthCheck: {
            healthy: boolean;
            message: string;
            name: string;
        };
        HealthResponse: {
            backend: string;
            checked_at: string;
            checks: components["schemas"]["HealthCheck"][];
            healthy: boolean;
            stove_server_version: string;
        };
        Hotspot: {
            frame: components["schemas"]["Frame"];
            /** Format: int64 */
            inclusive_value: number;
            /** Format: double */
            self_percent: number;
            /** Format: int64 */
            self_value: number;
        };
        IdleTimeoutRequest: {
            /**
             * Format: int64
             * @description Seconds without committed ingestion before abandonment; 0 disables expiry. Default: 3600.
             */
            run_idle_timeout_seconds: number;
        };
        LiveDashboardEvent: components["schemas"]["LiveDashboardPayload"] & {
            run_id: string;
            /** Format: int64 */
            seq: number;
        };
        LiveDashboardPayload: {
            /** @enum {string} */
            event_type: "run_started";
            payload: components["schemas"]["LiveRunStartedPayload"];
        } | {
            /** @enum {string} */
            event_type: "run_deleted";
            payload: components["schemas"]["LiveRunDeletedPayload"];
        } | {
            /** @enum {string} */
            event_type: "run_ended";
            payload: components["schemas"]["LiveRunEndedPayload"];
        } | {
            /** @enum {string} */
            event_type: "test_started";
            payload: components["schemas"]["LiveTestStartedPayload"];
        } | {
            /** @enum {string} */
            event_type: "test_ended";
            payload: components["schemas"]["LiveTestEndedPayload"];
        } | {
            /** @enum {string} */
            event_type: "entry_recorded";
            payload: components["schemas"]["LiveEntryRecordedPayload"];
        } | {
            /** @enum {string} */
            event_type: "span_recorded";
            payload: components["schemas"]["LiveSpanRecordedPayload"];
        } | {
            /** @enum {string} */
            event_type: "snapshot";
            payload: components["schemas"]["LiveSnapshotPayload"];
        } | {
            /** @enum {string} */
            event_type: "mock_interaction";
            payload: components["schemas"]["LiveMockInteractionPayload"];
        } | {
            /** @enum {string} */
            event_type: "mock_warning";
            payload: components["schemas"]["LiveMockWarningPayload"];
        };
        LiveEntryRecordedPayload: {
            action: string;
            actual: string | null;
            assertion_id: string;
            /** Format: int64 */
            attempt_count: number;
            error: string | null;
            expected: string | null;
            /** Format: int64 */
            failure_count: number;
            /** Format: int64 */
            id: number;
            input: string | null;
            metadata: string | null;
            output: string | null;
            result: components["schemas"]["TestStatus"];
            system: string;
            test_id: string;
            timestamp: string;
            trace_id: string | null;
        };
        LiveMockInteractionPayload: {
            attribution: string;
            /** Format: int64 */
            client_deadline_ms: number | null;
            /** Format: int64 */
            configured_delay_ms: number | null;
            fault: string | null;
            /** Format: int64 */
            id: number;
            /** Format: int64 */
            latency_ms: number | null;
            matched: boolean;
            method: string;
            near_misses: string[];
            next_scenario_state: string | null;
            protocol: string;
            request_body: string | null;
            request_body_truncated: boolean;
            response_body: string | null;
            response_body_truncated: boolean;
            scenario_name: string | null;
            scenario_state: string | null;
            status: string;
            stub_id: string | null;
            system: string;
            target: string;
            test_id: string | null;
            timestamp: string;
            trace_id: string | null;
        };
        LiveMockWarningPayload: {
            /** Format: int64 */
            id: number;
            kind: string;
            message: string;
            stub_id: string | null;
            system: string;
            target: string | null;
            test_id: string | null;
            timestamp: string;
        };
        LiveRunDeletedPayload: Record<string, never>;
        LiveRunEndedPayload: {
            /** Format: int64 */
            duration_ms: number;
            ended_at: string;
            /** Format: int32 */
            failed: number;
            /** Format: int32 */
            passed: number;
            status: components["schemas"]["RunStatus"];
            /** Format: int32 */
            total_tests: number;
        };
        LiveRunStartedPayload: {
            app_name: string;
            last_activity_at: string;
            metadata: {
                [key: string]: string;
            };
            started_at: string;
            stove_version: string | null;
            systems: string[];
        };
        LiveSnapshotPayload: {
            captured_at: string | null;
            /** Format: int64 */
            id: number;
            state_json: string;
            summary: string;
            system: string;
            test_id: string;
            trigger: string;
        };
        LiveSpanRecordedPayload: {
            attributes: string | null;
            /** Format: int64 */
            end_time_nanos: number;
            exception_message: string | null;
            exception_stack_trace: string | null;
            exception_type: string | null;
            /** Format: int64 */
            id: number;
            operation_name: string;
            parent_span_id: string | null;
            service_name: string;
            span_id: string;
            /** Format: int64 */
            start_time_nanos: number;
            status: components["schemas"]["SpanStatus"];
            test_id: string | null;
            trace_id: string;
        };
        LiveTestEndedPayload: {
            /** Format: int64 */
            duration_ms: number;
            ended_at: string;
            error: string | null;
            status: components["schemas"]["TestStatus"];
            test_id: string;
        };
        LiveTestStartedPayload: {
            spec_name: string;
            started_at: string;
            status: components["schemas"]["TestStatus"];
            test_id: string;
            test_name: string;
            test_path: string[];
        };
        McpMeta: {
            enabled: boolean;
            endpoint: string;
            scope: string;
            transport: string;
        };
        MetaResponse: {
            mcp: components["schemas"]["McpMeta"];
            stove_server_version: string;
        };
        /**
         * @description One completed exchange observed by a mock system (`WireMock` / gRPC Mock).
         *     `test_id` is `None` for unattributed evidence — attribution is proven-only,
         *     so those render in a run-level lane instead of being guessed into a test.
         */
        MockInteraction: {
            attribution: string;
            /** Format: int64 */
            client_deadline_ms: number | null;
            /** Format: int64 */
            configured_delay_ms: number | null;
            fault: string | null;
            /** Format: int64 */
            id: number;
            /** Format: int64 */
            latency_ms: number | null;
            matched: boolean;
            method: string;
            /** @description Rendered near-miss candidates; populated for unmatched exchanges. */
            near_misses: string[];
            next_scenario_state: string | null;
            protocol: string;
            request_body: string | null;
            request_body_truncated: boolean;
            response_body: string | null;
            response_body_truncated: boolean;
            run_id: string;
            scenario_name: string | null;
            scenario_state: string | null;
            status: string;
            stub_id: string | null;
            system: string;
            target: string;
            test_id: string | null;
            timestamp: string;
            trace_id: string | null;
        };
        /** @description A diagnostic a mock system observed — never a test failure. */
        MockWarning: {
            /** Format: int64 */
            id: number;
            kind: string;
            message: string;
            run_id: string;
            stub_id: string | null;
            system: string;
            target: string | null;
            test_id: string | null;
            timestamp: string;
        };
        ProfileReport: {
            app_name: string;
            interpretation: string;
            run_id: string;
            series: components["schemas"]["Series"][];
            truncated: boolean;
        };
        PurgePreview: {
            evidence: components["schemas"]["EvidenceCounts"];
            run_count: number;
            run_ids: string[];
        };
        PurgePreviewRequest: {
            app_name?: string | null;
            include_running?: boolean;
            /** @description Exact metadata values: any value within a key, all keys must match. Requires `app_name`. */
            metadata?: {
                [key: string]: string[];
            };
            older_than?: string | null;
        };
        PurgeRequest: {
            include_running?: boolean;
            run_ids: string[];
        };
        PurgeResult: {
            evidence: components["schemas"]["EvidenceCounts"];
            purged_run_ids: string[];
            purged_runs: number;
        };
        RetentionRequest: {
            runs_per_app: number;
        };
        /** @description A single test run (one execution of a test suite). */
        Run: {
            app_name: string;
            /** Format: int64 */
            duration_ms: number | null;
            ended_at: string | null;
            /** Format: int32 */
            failed: number;
            id: string;
            last_activity_at: string;
            metadata: {
                [key: string]: string;
            };
            /** Format: int32 */
            passed: number;
            started_at: string;
            status: components["schemas"]["RunStatus"];
            stove_version: string | null;
            systems: string[];
            /** Format: int32 */
            total_tests: number;
        };
        /**
         * @description Status of a test run.
         * @enum {string}
         */
        RunStatus: "RUNNING" | "PASSED" | "FAILED";
        Series: {
            findings: components["schemas"]["Finding"][];
            /** Format: int64 */
            from_ms: number;
            hotspots: components["schemas"]["Hotspot"][];
            metric: string;
            /** Format: int64 */
            omitted_stack_value: number;
            profile_ids: string[];
            sample_rates: number[];
            sample_records: number;
            service: string;
            stacks: components["schemas"]["Stack"][];
            /** Format: int64 */
            total: number;
            unit: string;
            /** Format: int64 */
            until_ms: number;
        };
        /** @description A system snapshot captured during a test. */
        Snapshot: {
            captured_at: string | null;
            /** Format: int64 */
            id: number;
            run_id: string;
            state_json: string;
            summary: string;
            system: string;
            test_id: string;
            /**
             * @description `TEST_END` for the regular end-of-test snapshot, `FAILURE` for the state
             *     captured at the moment the first failing entry was recorded.
             */
            trigger: string;
        };
        /** @description A span in a distributed trace. */
        Span: {
            attributes: string | null;
            /** Format: int64 */
            end_time_nanos: number;
            exception_message: string | null;
            exception_stack_trace: string | null;
            exception_type: string | null;
            /** Format: int64 */
            id: number;
            operation_name: string;
            parent_span_id: string | null;
            run_id: string;
            service_name: string;
            span_id: string;
            /** Format: int64 */
            start_time_nanos: number;
            status: components["schemas"]["SpanStatus"];
            trace_id: string;
        };
        /**
         * @description OpenTelemetry status; deliberately distinct from test outcomes.
         * @enum {string}
         */
        SpanStatus: "OK" | "ERROR" | "UNSET";
        Stack: {
            frames: components["schemas"]["Frame"][];
            /** Format: int64 */
            value: number;
        };
        StorageStats: {
            backend: string;
            evidence: components["schemas"]["EvidenceCounts"];
            retention_runs_per_app: number;
            /** Format: int64 */
            run_idle_timeout_seconds: number;
            /** Format: int64 */
            running_runs: number;
            /** Format: int64 */
            runs: number;
        };
        /** @description A single test within a run. */
        Test: {
            /** Format: int64 */
            duration_ms: number | null;
            ended_at: string | null;
            error: string | null;
            id: string;
            run_id: string;
            spec_name: string;
            started_at: string;
            status: components["schemas"]["TestStatus"];
            test_name: string;
            test_path: string[];
        };
        /**
         * @description Status of an individual test or entry result.
         * @enum {string}
         */
        TestStatus: "RUNNING" | "PASSED" | "FAILED" | "ERROR";
        TopFilterCatalog: {
            available_keys: string[];
            settings: components["schemas"]["TopFilterSettings"];
            values: components["schemas"]["TopFilterValue"][];
        };
        /** @description Shared dashboard filtering configuration. */
        TopFilterSettings: {
            metadata_key: string | null;
        };
        TopFilterValue: {
            /** @description Applications summarized from their latest run matching this value. */
            apps: components["schemas"]["AppSummary"][];
            value: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    execute_database_query: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DatabaseQueryRequest"];
            };
        };
        responses: {
            /** @description Database query result */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DatabaseQueryResult"];
                };
            };
            /** @description Invalid or rejected query */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Database connection unavailable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_database_schema: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Queryable database schema */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DatabaseSchema"];
                };
            };
        };
    };
    update_idle_timeout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["IdleTimeoutRequest"];
            };
        };
        responses: {
            /** @description Persisted idle timeout, applied on the next sweep */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StorageStats"];
                };
            };
            /** @description Invalid idle timeout */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    purge_runs: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PurgeRequest"];
            };
        };
        responses: {
            /** @description Purged data summary */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurgeResult"];
                };
            };
        };
    };
    preview_purge: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PurgePreviewRequest"];
            };
        };
        responses: {
            /** @description Data that would be purged */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurgePreview"];
                };
            };
            /** @description Invalid purge criteria */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    update_retention: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RetentionRequest"];
            };
        };
        responses: {
            /** @description Updated storage and retention status */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StorageStats"];
                };
            };
        };
    };
    get_admin_status: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Storage and retention status */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StorageStats"];
                };
            };
        };
    };
    update_top_filter: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TopFilterSettings"];
            };
        };
        responses: {
            /** @description Saved dashboard top filter */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TopFilterSettings"];
                };
            };
            /** @description Invalid metadata key */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_agent_setup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Browser-visible MCP URL, used only when `public_url` is not configured. */
                endpoint: string | null;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Read-only agent installation instructions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AgentSetupResponse"];
                };
            };
            /** @description Missing or invalid MCP URL */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_apps: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Known applications */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AppSummary"][];
                };
            };
        };
    };
    clear_all: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All dashboard data was cleared */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    post_event: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Binary stove.dashboard.v1.DashboardEvent generated from stove-dashboard-api */
        requestBody: {
            content: {
                "application/x-protobuf": string;
            };
        };
        responses: {
            /** @description Event committed or recognized as a duplicate */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/x-protobuf": string;
                };
            };
            /** @description Malformed protobuf or invalid dashboard event */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Event could not be persisted */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Database connection unavailable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    sse_handler: {
        parameters: {
            query?: never;
            header?: {
                /** @description Resume after this event */
                "last-event-id"?: number | null;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Live dashboard event stream. Each data field contains a JSON LiveDashboardEvent; keep-alives are SSE comments. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": unknown;
                };
            };
        };
    };
    get_health: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Server and storage are responding */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HealthResponse"];
                };
            };
            /** @description A health check failed or timed out */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HealthResponse"];
                };
            };
        };
    };
    get_meta: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Server version and capabilities */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MetaResponse"];
                };
            };
        };
    };
    get_runs: {
        parameters: {
            query?: {
                app?: string;
                /** @description URL-encoded JSON object containing an exact metadata subset to match. */
                metadata?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Runs matching the filters */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Run"][];
                };
            };
            /** @description Invalid metadata filter */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_run: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Run, or null when it does not exist */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Run"] | null;
                };
            };
        };
    };
    get_run_focused_evidence: {
        parameters: {
            query?: {
                context?: number;
            };
            header?: never;
            path: {
                id: number;
                kind: components["schemas"]["EvidenceKind"];
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FocusedEvidence"];
                };
            };
            /** @description Run evidence unavailable */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_run_mock_interactions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All mock interactions in the run */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockInteraction"][];
                };
            };
        };
    };
    get_unattributed_run_mock_interactions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Mock interactions not attributed to a test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockInteraction"][];
                };
            };
        };
    };
    get_run_mock_warnings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All mock diagnostics in the run */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockWarning"][];
                };
            };
        };
    };
    get_unattributed_run_mock_warnings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Mock diagnostics not attributed to a test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockWarning"][];
                };
            };
        };
    };
    get_profiles: {
        parameters: {
            query?: {
                from_ms?: number;
                instance?: string;
                metric?: string;
                service?: string;
                span_id?: string;
                trace_id?: string;
                until_ms?: number;
            };
            header?: never;
            path: {
                /** @description Exact Stove run */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProfileReport"];
                };
            };
        };
    };
    get_tests: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Tests in the run */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Test"][];
                };
            };
        };
    };
    get_test: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                run_id: string;
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Test"];
                };
            };
            /** @description Test unavailable */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_entries: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Collapsed report entries */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Entry"][];
                };
            };
        };
    };
    get_raw_entries: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Uncollapsed report entries */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Entry"][];
                };
            };
        };
    };
    get_focused_evidence: {
        parameters: {
            query?: {
                /** @description Surrounding records, default 10, maximum 100 */
                context?: number;
            };
            header?: never;
            path: {
                id: number;
                kind: components["schemas"]["EvidenceKind"];
                run_id: string;
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FocusedEvidence"];
                };
            };
            /** @description Evidence unavailable in this scope */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    get_test_mock_interactions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Mock interactions attributed to the test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockInteraction"][];
                };
            };
        };
    };
    get_test_mock_warnings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Mock diagnostics attributed to the test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MockWarning"][];
                };
            };
        };
    };
    get_snapshots: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description System snapshots for the test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Snapshot"][];
                };
            };
        };
    };
    get_test_spans: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Run identifier */
                run_id: string;
                /** @description Test identifier */
                test_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trace spans for the test */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Span"][];
                };
            };
        };
    };
    get_top_filter: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Configured top filter and recorded metadata values */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TopFilterCatalog"];
                };
            };
        };
    };
    get_trace: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Trace identifier */
                trace_id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All spans in the trace */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Span"][];
                };
            };
        };
    };
    get_metrics: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Prometheus application and database metrics */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/plain": string;
                };
            };
        };
    };
}
