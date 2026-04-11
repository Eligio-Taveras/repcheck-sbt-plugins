package com.example

object Service {
  // Only project exceptions — should pass
  def doSomething(): Unit = throw MyAppException("something went wrong")
}
