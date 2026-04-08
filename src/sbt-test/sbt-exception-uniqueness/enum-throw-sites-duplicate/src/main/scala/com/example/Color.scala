package com.example

final case class InvalidColor(name: String) extends RuntimeException(s"Invalid: $name")

enum Color(val label: String) {
  case Red   extends Color("red")
  case Green extends Color("green")
  case Blue  extends Color("blue")
}

object Color {
  def parse(s: String): Color = s match {
    case "red"   => Color.Red
    case "green" => Color.Green
    case "blue"  => Color.Blue
    case other   => throw new InvalidColor(other)
  }

  def parseStrict(s: String): Color = s match {
    case "red"   => Color.Red
    case other   => throw new InvalidColor(other)
  }
}
