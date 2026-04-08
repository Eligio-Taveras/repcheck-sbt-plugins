package com.example

object Thrower {
  def boom(): Nothing =
    throw FooException("only one apply-form site")
}
