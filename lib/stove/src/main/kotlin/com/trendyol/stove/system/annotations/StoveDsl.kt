package com.trendyol.stove.system.annotations

/**
 * DSL marker annotation for Stove's type-safe builder pattern.
 *
 * This annotation is used to scope DSL functions and prevent accidental access
 * to outer receivers in nested lambdas, ensuring type safety in the test DSL.
 *
 * ## Purpose
 *
 * When writing nested DSL blocks, Kotlin allows implicit access to outer receivers.
 * This can lead to confusing code where it's unclear which receiver a method belongs to.
 * [StoveDsl] prevents this by marking all Stove DSL components.
 *
 * ## Example
 *
 * ```kotlin
 * // Without @DslMarker, this would compile but be confusing:
 * stove {
 *     http {
 *         kafka {  // Accidentally nested - should be at validation level
 *             // ...
 *         }
 *     }
 * }
 *
 * // With @StoveDsl, the above code won't compile, forcing correct structure:
 * stove {
 *     http {
 *         get<User>("/users/1") { /* ... */ }
 *     }
 *     kafka {  // Correctly at validation level
 *         shouldBePublished<Event> { /* ... */ }
 *     }
 * }
 * ```
 *
 * ## When to Use
 *
 * Apply this annotation when creating:
 * - Custom [PluggedSystem] implementations
 * - DSL extension functions for systems
 * - Options classes used in configuration DSL
 * - Any class that participates in Stove's builder pattern
 *
 * ```kotlin
 * @StoveDsl
 * class MyCustomSystem(override val stove: Stove) : PluggedSystem {
 *     @StoveDsl
 *     fun myDslMethod(): MyCustomSystem {
 *         // ...
 *         return this
 *     }
 * }
 * ```
 *
 * @see DslMarker
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class StoveDsl
