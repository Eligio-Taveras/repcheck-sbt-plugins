sys.props.get("plugin.version") match {
  case Some(x) => addSbtPlugin("com.repcheck" % "sbt-exception-uniqueness" % x)
  case _       => sys.error("'plugin.version' environment variable is not set")
}
