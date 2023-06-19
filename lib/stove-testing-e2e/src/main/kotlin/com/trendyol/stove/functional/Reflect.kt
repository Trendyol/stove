package com.trendyol.stove.functional

import kotlin.reflect.KProperty

class Reflect<T : Any>(val instance: T) {
    inner class OnGoingReflect<R>(
        private val instance: T,
        private val property: String
    ) {
        infix fun then(value: R) {
            val prop = instance::class.java.getDeclaredField(property)
            prop.isAccessible = true
            prop.set(instance, value)
        }
    }

    inline fun <reified R> on(
        propertySelector: T.() -> KProperty<R>
    ): OnGoingReflect<R> = OnGoingReflect(instance, propertySelector(instance).name)

    companion object {
        inline operator fun <reified T : Any> invoke(
            instance: T,
            block: Reflect<T>.() -> Unit
        ): Reflect<T> {
            val ref = Reflect(instance)
            block(ref)
            return ref
        }
    }
}
