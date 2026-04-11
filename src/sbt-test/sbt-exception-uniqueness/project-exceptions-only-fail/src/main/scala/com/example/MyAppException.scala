package com.example

final case class MyAppException(detail: String) extends Exception(s"App error: $detail")
