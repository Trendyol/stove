package com.trendyol.stove.wiremock

internal object WireMockHeaders {
  const val CONTENT_TYPE = "Content-Type"
  const val APPLICATION_JSON = "application/json"
  const val APPLICATION_JSON_UTF8 = "application/json; charset=UTF-8"
}

internal object WireMockUrls {
  fun baseUrl(host: String, port: Int): String = "http://$host:$port"
}

internal object WireMockReportSystem {
  fun name(keyName: String?): String =
    "WireMock" + (keyName?.let { " [$it]" } ?: "")
}

internal object WireMockSystemMessages {
  fun systemNotRegistered(keyName: String): String =
    "No WireMockSystem registered with key '$keyName'"
}

internal object WireMockReportMetadataKeys {
  const val STATUS_CODE = "statusCode"
  const val RESPONSE_HEADERS = "responseHeaders"
}

internal object WireMockReportActions {
  fun registerStub(method: String, url: String): String = "Register stub: $method $url"

  fun registerCustomStub(method: String, url: String): String = "Register stub: $method $url (custom)"

  fun registerPartialStub(url: String): String = "Register stub: $url (partial match)"

  fun registerBehaviourStub(url: String): String = "Register behaviour stub: $url"

  fun registerFaultStub(method: String, url: String, fault: String): String = "Register fault stub: $method $url ($fault)"

  fun registerDynamicStub(method: String, url: String): String = "Register stub: $method $url (dynamic response)"

  const val VALIDATE_ALL_REQUESTS_SHOULD_MATCH = "Validate: All requests should match registered stubs"
  const val VALIDATE_ALL_REQUESTS_MATCHED = "Validate: All requests matched registered stubs"
  const val VERIFY_REQUEST_WAS_CALLED = "Verify request was called"
  const val VERIFY_REQUEST_WAS_NOT_CALLED = "Verify request was not called"
}

internal object WireMockValidationMessages {
  const val REQUEST_CONTAINING_EMPTY = "requestContaining must not be empty"
  const val VALIDATION_FAILED = "Validation failed"
  const val EXPECTED_NO_UNMATCHED_REQUESTS = "0 unmatched requests"
  const val STOP_FAILED_PREFIX = "got an error while stopping wiremock:"

  fun unmatchedRequests(problems: String): String =
    "There are unmatched requests in the mock pipeline, please satisfy all the wanted requests.\n$problems"

  fun unmatchedRequestCount(count: Int): String = "$count unmatched request(s)"

  fun requestCount(count: Int): String = "$count request(s)"

  fun unmatchedRequestDetails(
    url: String,
    bodyAsString: String,
    queryParams: String
  ): String =
    """
        Url: $url
        Body: $bodyAsString
        QueryParams: $queryParams
    """.trimIndent()
}

internal object WireMockSnapshotStateKeys {
  const val REGISTERED_STUBS = "registeredStubs"
  const val ACTIVE_STUBS = "activeStubs"
  const val RECEIVED_REQUESTS = "receivedRequests"
  const val RECORDED_REQUESTS = "recordedRequests"
  const val SERVED_REQUESTS = "servedRequests"
  const val UNMATCHED_REQUESTS = "unmatchedRequests"
}

internal object WireMockSnapshotFieldKeys {
  const val ID = "id"
  const val NAME = "name"
  const val ACTIVE = "active"
  const val PRIORITY = "priority"
  const val SCENARIO_NAME = "scenarioName"
  const val REQUIRED_SCENARIO_STATE = "requiredScenarioState"
  const val NEW_SCENARIO_STATE = "newScenarioState"
  const val REQUEST = "request"
  const val RESPONSE = "response"
  const val RESPONSE_DEFINITION = "responseDefinition"
  const val METADATA = "metadata"
  const val METHOD = "method"
  const val URL = "url"
  const val STATUS = "status"
  const val STATUS_MESSAGE = "statusMessage"
  const val MATCHED = "matched"
  const val STUB_ID = "stubId"
  const val STUB_NAME = "stubName"
  const val TIMING = "timing"
  const val ADDED_DELAY_MS = "addedDelayMs"
  const val PROCESS_TIME_MS = "processTimeMs"
  const val RESPONSE_SEND_TIME_MS = "responseSendTimeMs"
  const val SERVE_TIME_MS = "serveTimeMs"
  const val TOTAL_TIME_MS = "totalTimeMs"
  const val ABSOLUTE_URL = "absoluteUrl"
  const val CLIENT_IP = "clientIp"
  const val LOGGED_DATE = "loggedDate"
  const val HEADERS = "headers"
  const val QUERY_PARAMS = "queryParams"
  const val BODY = "body"
  const val URL_MATCHER = "urlMatcher"
  const val BODY_PATTERNS = "bodyPatterns"
  const val CUSTOM_MATCHER = "customMatcher"
  const val BODY_FILE_NAME = "bodyFileName"
  const val FAULT = "fault"
  const val FIXED_DELAY_MS = "fixedDelayMs"
  const val TRANSFORMERS = "transformers"
  const val MIME_TYPE = "mimeType"
}

internal object WireMockSnapshotSummary {
  fun registeredStubs(total: Int, active: Int): String = "Registered stubs (this test): $total (active: $active)"

  fun receivedRequests(total: Int): String = "Received requests (this test): $total"

  fun servedRequests(total: Int, matched: Int): String = "Served requests (this test): $total (matched: $matched)"

  fun unmatchedRequests(total: Int): String = "Unmatched requests: $total"
}

internal object WireMockSnapshotDisplayValues {
  const val CUSTOM_MATCHER = "<custom matcher>"
}

internal object WireMockBehaviourMessages {
  const val INITIALLY_ONCE = "You should call initially only once"
  const val INITIALLY_BEFORE_THEN = "You should call initially before calling then"
  const val FAILS_TIMES_FIRST = "failsTimes starts a behaviour; call it before initially/then"
  const val FAILS_TIMES_POSITIVE = "failsTimes requires times >= 1"
}

internal object WireMockBehaviourNames {
  fun scenarioName(url: String): String = "Scenario for $url"

  fun state(counter: Int): String = "State$counter"
}

internal object WireMockJsonPath {
  fun field(key: String): String = "\$.$key"
}

internal object WireMockExtensionNames {
  const val VACUUM_CLEANER = "StoveVacuumCleaner"
}
