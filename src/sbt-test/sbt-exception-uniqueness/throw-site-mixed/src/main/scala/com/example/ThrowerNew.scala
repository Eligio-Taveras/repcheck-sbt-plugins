package com.example

object ThrowerNew {
  def boom(): Nothing =
    throw new FooException("new form")
}
