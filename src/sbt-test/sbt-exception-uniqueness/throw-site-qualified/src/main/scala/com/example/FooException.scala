package com.example

final case class FooException(msg: String) extends RuntimeException(msg)
