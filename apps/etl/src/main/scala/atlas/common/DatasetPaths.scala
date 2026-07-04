package atlas.common

import atlas.config.ReceitaConfig

final case class DatasetPaths(input: String, output: String, qualityJson: String, qualityMarkdown: String)
object DatasetPaths {
  def estabelecimentos(c: ReceitaConfig): DatasetPaths = {
    val raw = c.rawDir.stripSuffix("/").stripSuffix("\\")
    val bronze = c.bronzeDir.stripSuffix("/").stripSuffix("\\")
    DatasetPaths(s"$raw/*", s"$bronze/estabelecimentos", s"$bronze/estabelecimentos_quality_report.json", s"$bronze/estabelecimentos_quality_report.md")
  }

  def silverEstablishments(c: ReceitaConfig): DatasetPaths = {
    val bronze = c.bronzeDir.stripSuffix("/").stripSuffix("\\")
    val silver = c.silverDir.stripSuffix("/").stripSuffix("\\")
    DatasetPaths(
      s"$bronze/estabelecimentos",
      s"$silver/establishments",
      s"$silver/establishments_quality_report.json",
      s"$silver/establishments_quality_report.md"
    )
  }
}
