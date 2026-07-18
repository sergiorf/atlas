package atlas.release

import atlas.config.AtlasConfig
import java.nio.file.{Path, Paths}

final case class ReleasePaths(config: AtlasConfig) {
  val release: ReleaseId = ReleaseId.unsafe(config.receita.snapshot)
  val dataRoot: Path = commonRoot(Paths.get(config.receita.rawDir), Paths.get(config.receita.bronzeDir), Paths.get(config.receita.silverDir))
  val atlasRoot: Path = Option(Paths.get(config.statusDir).getParent).getOrElse(Paths.get("data/_atlas"))

  def rawRoot: Path = {
    Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, release))
  }
  def bronzeRelease: Path = Paths.get(config.receita.bronzeDir).resolve("estabelecimentos").resolve(s"release=${release.value}")
  def bronzeReports: Path = atlasRoot.resolve("reports/receita/estabelecimentos").resolve(release.value).resolve("bronze")
  def silverCurrent: Path = Paths.get(config.receita.silverDir).resolve("establishments_current")
  def silverCandidate: Path = atlasRoot.resolve("work/receita/estabelecimentos").resolve(s"release=${release.value}").resolve("silver_candidate")
  def silverReports: Path = atlasRoot.resolve("reports/receita/estabelecimentos").resolve(release.value).resolve("silver")
  def historyRoot: Path = Paths.get(config.receita.silverDir).resolve("establishment_change_events")
  def historyRelease: Path = historyRoot.resolve(s"to_release=${release.value}")
  def summaryRoot: Path = Paths.get(config.receita.silverDir).resolve("establishment_release_summaries")
  def summaryRelease: Path = summaryRoot.resolve(s"to_release=${release.value}")
  def trashRoot(timestamp: String): Path = atlasRoot.resolve("_trash").resolve(timestamp).resolve(s"release=${release.value}")

  def derivedPaths(layer: ReleaseLayer): Seq[(String, Path)] = layer match {
    case ReleaseLayer.Bronze => Seq("bronze" -> bronzeRelease)
    case ReleaseLayer.Silver => Seq("silver_candidate" -> silverCandidate)
    case ReleaseLayer.Reports => Seq("bronze_reports" -> bronzeReports, "silver_reports" -> silverReports)
    case ReleaseLayer.History => Seq("history" -> historyRelease, "summary" -> summaryRelease)
    case ReleaseLayer.AllDerived =>
      derivedPaths(ReleaseLayer.Bronze) ++ derivedPaths(ReleaseLayer.Silver) ++
        derivedPaths(ReleaseLayer.Reports) ++ derivedPaths(ReleaseLayer.History)
  }

  private def commonRoot(paths: Path*): Path = {
    val normalized = paths.map(_.normalize())
    normalized
      .flatMap(path => Iterator.iterate(path)(_.getParent).takeWhile(_ != null).toSeq)
      .find(candidate => normalized.forall(_.startsWith(candidate)))
      .getOrElse(Paths.get("data"))
  }
}

object ReleasePaths {
  private val ReleasePattern = "([0-9]{4}-[0-9]{2})".r

  def rawDirForRelease(rawDir: String, release: ReleaseId): String =
    ReleasePattern.findFirstMatchIn(rawDir).fold(rawDir) { matched =>
      rawDir.substring(0, matched.start) + release.value + rawDir.substring(matched.end)
    }
}
