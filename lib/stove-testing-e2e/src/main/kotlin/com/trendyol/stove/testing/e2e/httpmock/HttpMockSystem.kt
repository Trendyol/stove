package com.trendyol.stove.testing.e2e.httpmock

import arrow.core.None
import arrow.core.Option
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ValidatedSystem

/**
 * Mocking abstraction for http services
 * @author Oguzhan Soykan
 */
interface HttpMockSystem<TRequestBuilder : Any> : PluggedSystem, ValidatedSystem {

    /**
     * Mocks a GET request for the relative url
     */
    fun mockGet(
        url: String,
        responseBody: Option<Any>,
        statusCode: Int = 200,
        metadata: Map<String, Any> = mapOf(),
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Mocks a POST request for the relative url
     */
    fun mockPost(
        url: String,
        requestBody: Option<Any> = None,
        responseBody: Option<Any> = None,
        statusCode: Int = 200,
        metadata: Map<String, Any> = mapOf(),
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Mocks a PUT request for the relative url
     */
    fun mockPut(
        url: String,
        requestBody: Option<Any> = None,
        responseBody: Option<Any> = None,
        statusCode: Int,
        metadata: Map<String, Any> = mapOf(),
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Mocks a DELETE request for the relative url
     */
    fun mockDelete(
        url: String,
        statusCode: Int,
        metadata: Map<String, Any> = mapOf(),
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Mocks a HEAD request for the relative url
     */
    fun mockHead(
        url: String,
        statusCode: Int = 200,
        metadata: Map<String, Any> = mapOf(),
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Configures and allows use to decorate the POST mock request
     */
    fun mockPostConfigure(
        url: String,
        configure: (TRequestBuilder, ObjectMapper) -> TRequestBuilder,
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Configures and allows use to decorate the GET mock request
     */
    fun mockGetConfigure(
        url: String,
        configure: (TRequestBuilder, ObjectMapper) -> TRequestBuilder,
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Configures and allows use to decorate the HEAD mock request
     */
    fun mockHeadConfigure(
        url: String,
        configure: (TRequestBuilder, ObjectMapper) -> TRequestBuilder,
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Configures and allows use to decorate the DELETE mock request
     */
    fun mockDeleteConfigure(
        url: String,
        configure: (TRequestBuilder, ObjectMapper) -> TRequestBuilder,
    ): HttpMockSystem<TRequestBuilder>

    /**
     * Configures and allows use to decorate the PUT mock request
     */
    fun mockPutConfigure(
        url: String,
        configure: (TRequestBuilder, ObjectMapper) -> TRequestBuilder,
    ): HttpMockSystem<TRequestBuilder>
}
