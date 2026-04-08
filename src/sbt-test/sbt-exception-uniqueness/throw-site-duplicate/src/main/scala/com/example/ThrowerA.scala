package com.example

object ThrowerA {
  def boom(): Nothing =
    throw new FooException("site A")
}
