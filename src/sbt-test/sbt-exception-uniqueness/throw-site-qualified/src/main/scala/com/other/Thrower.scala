package com.other

object Thrower {
  def boom(): Nothing =
    throw com.example.FooException("qualified apply site")
}
