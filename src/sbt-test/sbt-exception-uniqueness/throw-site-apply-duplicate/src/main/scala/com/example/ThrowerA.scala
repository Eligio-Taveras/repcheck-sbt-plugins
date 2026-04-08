package com.example

object ThrowerA {
  def boom(): Nothing =
    throw FooException("apply site A")
}
