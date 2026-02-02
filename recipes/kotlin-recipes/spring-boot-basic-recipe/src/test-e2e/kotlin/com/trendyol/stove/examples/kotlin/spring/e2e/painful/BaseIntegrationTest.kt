package com.trendyol.stove.examples.kotlin.spring.e2e.painful

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * THE PAIN - Traditional Integration Test Setup
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * This file demonstrates the typical boilerplate required for integration testing
 * WITHOUT Stove. Open this during your presentation and ask:
 *
 * "How many of you have something like this in your codebase?"
 *
 * This is NOT meant to be executed - it's a demonstration of the pain points.
 * ════════════════════════════════════════════════════════════════════════════════
 */

/*

// 50+ LINES OF BOILERPLATE BEFORE YOU CAN WRITE A SINGLE TEST

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class BaseIntegrationTest {
    
    companion object {
        // ── Container declarations ──────────────────────────────────────────
        // One container per dependency, manual lifecycle management
        
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
        
        @Container  
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"))
            .withStartupAttempts(3)
        
        val wiremock = WireMockServer(WireMockConfiguration.options().dynamicPort())
        
        // ── Manual lifecycle hooks ──────────────────────────────────────────
        // Forgot @AfterAll? Containers leak!
        
        @JvmStatic
        @BeforeAll
        fun startWiremock() { 
            wiremock.start() 
        }
        
        @JvmStatic
        @AfterAll
        fun stopWiremock() { 
            wiremock.stop() 
        }
        
        // ── Property binding ceremony ───────────────────────────────────────
        // Copy-paste this for EVERY property, grows linearly with dependencies
        
        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // Database - 3 properties
            registry.add("spring.r2dbc.url") { 
                "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" 
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }
            
            // Kafka - 2 properties
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("spring.kafka.producer.properties.interceptor.classes") { "" }
            
            // WireMock - 2 properties
            registry.add("external-apis.inventory.url") { "http://localhost:${wiremock.port()}" }
            registry.add("external-apis.payment.url") { "http://localhost:${wiremock.port()}" }
            
            // Add more dependencies? Add more properties here...
        }
    }
    
    // ── Inject every dependency you need ────────────────────────────────────
    
    @LocalServerPort
    protected var port: Int = 0
    
    @Autowired
    protected lateinit var r2dbcEntityTemplate: R2dbcEntityTemplate
    
    @Autowired
    protected lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    
    @Autowired
    protected lateinit var orderRepository: OrderRepository
    
    @Autowired
    protected lateinit var objectMapper: ObjectMapper
    
    // Need to test another bean? Add another @Autowired here...
    
    // ── Cleanup ritual ──────────────────────────────────────────────────────
    // Pray you remember to clean everything
    
    @BeforeEach
    fun setup() {
        RestAssured.port = port
        RestAssured.baseURI = "http://localhost"
        wiremock.resetAll()
        
        // Database cleanup - hope you didn't miss a table!
        runBlocking {
            r2dbcEntityTemplate.databaseClient
                .sql("TRUNCATE orders CASCADE")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        
        // Kafka cleanup? Good luck with that...
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// NOW you can write a test... but with THREE different assertion APIs
// ════════════════════════════════════════════════════════════════════════════════

class OrderControllerPainfulTest : BaseIntegrationTest() {
    
    @Test
    fun `should create order with all verifications`() {
        val userId = UUID.randomUUID().toString()
        val productId = "macbook-pro-16"
        val amount = 2499.99
        
        // ── WireMock setup (its own API) ────────────────────────────────────
        wiremock.stubFor(
            get(urlEqualTo("/inventory/$productId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "productId": "$productId",
                                "available": true,
                                "quantity": 10
                            }
                        """.trimIndent())
                )
        )
        
        wiremock.stubFor(
            post(urlEqualTo("/payments/charge"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "success": true,
                                "transactionId": "txn-123",
                                "amount": $amount
                            }
                        """.trimIndent())
                )
        )
        
        // ── HTTP call with RestAssured (API #1) ─────────────────────────────
        val response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "userId": "$userId",
                    "productId": "$productId",
                    "amount": $amount
                }
            """.trimIndent())
        .`when`()
            .post("/api/orders")
        .then()
            .statusCode(201)
            .extract()
            .asString()
        
        val orderResponse = objectMapper.readValue(response, OrderResponse::class.java)
        
        // ── Database verification with R2DBC (API #2) ───────────────────────
        // Completely different syntax than HTTP assertions
        runBlocking {
            val orders = r2dbcEntityTemplate.databaseClient
                .sql("SELECT * FROM orders WHERE user_id = :userId")
                .bind("userId", userId)
                .fetch()
                .all()
                .asFlow()
                .toList()
            
            assertEquals(1, orders.size)
            assertEquals("CONFIRMED", orders.first()["status"])
            assertEquals(amount, (orders.first()["amount"] as BigDecimal).toDouble())
        }
        
        // ── Kafka verification with KafkaTestUtils (API #3) ─────────────────
        // Yet another API pattern to learn
        val consumer = createConsumer()
        consumer.subscribe(listOf("showcase.orders.created"))
        
        val records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
        assertTrue(records.count() > 0) { "Expected at least one Kafka message" }
        
        val event = objectMapper.readValue(
            records.first().value() as String, 
            OrderCreatedEvent::class.java
        )
        assertEquals(userId, event.userId)
        assertEquals(productId, event.productId)
        
        consumer.close()
    }
    
    private fun createConsumer(): KafkaConsumer<String, String> {
        // 10 more lines of Kafka consumer setup...
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        }
        return KafkaConsumer(props)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// SUMMARY OF PAIN POINTS:
// ════════════════════════════════════════════════════════════════════════════════
//
// 1. 50+ lines of boilerplate BEFORE writing a single test
// 2. Three different assertion APIs (RestAssured, R2DBC, KafkaTestUtils)
// 3. Manual container lifecycle management
// 4. Manual property binding ceremony
// 5. Manual cleanup that's easy to forget
// 6. No unified DSL - each technology has its own patterns
// 7. When tests fail, you get cryptic stack traces
// 8. Copy this BaseIntegrationTest to 50 test classes...
//
// Compare this with TheShowcase.kt using Stove!
// ════════════════════════════════════════════════════════════════════════════════

*/
