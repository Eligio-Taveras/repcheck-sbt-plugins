package com.example

object BadService {
  // Uses a standard exception in production code — should FAIL the check
  def doSomething(): Unit = throw new RuntimeException("this should fail")
}
