package atlas.common

import atlas.config.ReceitaConfig
import atlas.release.ReleasePaths
import java.nio.file.Paths

final case class DatasetPaths(
    input: String,
    output: String,
    qualityJson: String,
    qualityMarkdown: String,
    malformedRows: String = "",
    duplicateCnpjFull: String = ""
)
object DatasetPaths {
  def estabelecimentos(c: ReceitaConfig): DatasetPaths = {
    val raw = c.rawDir.stripSuffix("/").stripSuffix("\\")
    val bronze = c.bronzeDir.stripSuffix("/").stripSuffix("\\")
    val bronzePath = Paths.get(c.bronzeDir)
    val dataRoot = Option(bronzePath.getParent).flatMap(path => Option(path.getParent)).getOrElse(Paths.get("data"))
    val reports = dataRoot.resolve("_atlas/reports/receita/estabelecimentos").resolve(c.snapshot).resolve("bronze")
    DatasetPaths(
      s"$raw/*",
      s"$bronze/estabelecimentos/release=${c.snapshot}",
      reports.resolve("quality.json").toString,
      reports.resolve("quality.md").toString
    )
  }

  def silverEstablishments(config: atlas.config.AtlasConfig): DatasetPaths = {
    val c = config.receita
    val paths = ReleasePaths(config)
    val status = java.nio.file.Paths.get(config.statusDir)
    val atlasRoot = Option(status.getParent).getOrElse(java.nio.file.Paths.get("data/_atlas"))
    val quality = atlasRoot.resolve("quality/receita/establishments").resolve(c.snapshot)
    DatasetPaths(
      paths.bronzeRelease.toString,
      paths.silverCurrent.toString,
      paths.silverReports.resolve("quality.json").toString,
      paths.silverReports.resolve("quality.md").toString,
      quality.resolve("malformed_rows").toString,
      quality.resolve("duplicate_cnpj_full").toString
    )
  }
}
