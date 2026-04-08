package com.example

object ThrowerB {
  def boom(): Nothing =
    throw new FooException("site B")
}
