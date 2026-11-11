package com.trendyol.stove.extensions.cucumber

/** A suspending Cucumber step. Use this annotation or its Gherkin keyword aliases. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Step(val value: String)

typealias Given = Step
typealias When = Step
typealias Then = Step
typealias And = Step
typealias But = Step
