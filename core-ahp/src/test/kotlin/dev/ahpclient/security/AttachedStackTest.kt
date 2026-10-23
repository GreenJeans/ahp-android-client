package dev.ahpclient.security

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AttachedStackTest {
    private class Screen(val name: String)

    private val a = Screen("a")
    private val b = Screen("b")
    private val c = Screen("c")

    @Test
    fun `nothing attached means nothing to ask`() {
        assertNull(AttachedStack<Screen>().top())
    }

    @Test
    fun `the newest screen is the one asked`() {
        val stack = AttachedStack<Screen>().apply { attach(a); attach(b) }

        assertSame(b, stack.top())
    }

    @Test
    fun `when the top screen goes away the one below is asked again`() {
        val stack = AttachedStack<Screen>().apply { attach(a); attach(b) }

        stack.detach(b)

        assertSame(a, stack.top())
        stack.detach(a)
        assertNull(stack.top())
    }

    @Test
    fun `a screen below the top that goes away leaves the top alone`() {
        val stack = AttachedStack<Screen>().apply { attach(a); attach(b); attach(c) }

        stack.detach(b)

        assertSame(c, stack.top())
        stack.detach(c)
        assertSame(a, stack.top())
    }

    @Test
    fun `attaching a screen that is already there moves it to the top and does not list it twice`() {
        val stack = AttachedStack<Screen>().apply { attach(a); attach(b); attach(a) }

        assertSame(a, stack.top())
        stack.detach(a)
        assertSame(b, stack.top())
        stack.detach(b)
        assertNull(stack.top(), "a was listed once, so one detach removed it")
    }

    @Test
    fun `detaching a screen that was never attached changes nothing`() {
        val stack = AttachedStack<Screen>().apply { attach(a) }

        stack.detach(b)

        assertSame(a, stack.top())
    }
}
