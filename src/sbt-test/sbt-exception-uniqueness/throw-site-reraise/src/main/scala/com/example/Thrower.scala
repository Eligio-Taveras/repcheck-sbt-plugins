package com.example

object Thrower {
  def boom(): Nothing =
    throw new FooException("the only construction site")

  def reraise(): Unit =
    try {
      boom()
    } catch {
      case e: FooException => throw e
    }

  def rethrowLocal(): Unit = {
    val captured: Throwable = new RuntimeException("not a project exception")
    throw captured
  }
}
