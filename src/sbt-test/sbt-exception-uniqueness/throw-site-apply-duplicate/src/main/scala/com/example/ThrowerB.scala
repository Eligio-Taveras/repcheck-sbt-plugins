package com.example

object ThrowerB {
  def boom(): Nothing =
    throw FooException("apply site B")
}
