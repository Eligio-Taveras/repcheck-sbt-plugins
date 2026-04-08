package com.example

object ThrowerApply {
  def boom(): Nothing =
    throw FooException("apply form")
}
