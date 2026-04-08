package com.example

object Thrower {
  def boom(): Nothing =
    throw new FooException("only one site")
}
