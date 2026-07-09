package atlas.release

final case class ReleaseId private (value: String) extends AnyVal {
  override def toString: String = value
}

object ReleaseId {
  private val Pattern = raw"^([0-9]{4})-([0-9]{2})$$".r

  def parse(value: String): Either[String, ReleaseId] = value match {
    case Pattern(_, month) if month.toInt >= 1 && month.toInt <= 12 =>
      Right(ReleaseId(value))
    case _ =>
      Left(s"Invalid release id '$value'; expected YYYY-MM with month 01-12")
  }

  def unsafe(value: String): ReleaseId =
    parse(value).fold(message => throw new IllegalArgumentException(message), identity)
}
