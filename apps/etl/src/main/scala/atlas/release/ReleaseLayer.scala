package atlas.release

sealed abstract class ReleaseLayer(val name: String)

object ReleaseLayer {
  case object Bronze extends ReleaseLayer("bronze")
  case object Silver extends ReleaseLayer("silver")
  case object Reports extends ReleaseLayer("reports")
  case object History extends ReleaseLayer("history")
  case object AllDerived extends ReleaseLayer("all-derived")

  val values: Seq[ReleaseLayer] = Seq(Bronze, Silver, Reports, History, AllDerived)

  def parse(value: String): Either[String, ReleaseLayer] =
    values.find(_.name == value).toRight(
      s"Invalid layer '$value'; expected one of ${values.map(_.name).mkString(", ")}"
    )
}
