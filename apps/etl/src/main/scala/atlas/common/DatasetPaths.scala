package atlas.common

import atlas.config.ReceitaConfig

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
    DatasetPaths(s"$raw/*", s"$bronze/estabelecimentos", s"$bronze/estabelecimentos_quality_report.json", s"$bronze/estabelecimentos_quality_report.md")
  }

  def silverEstablishments(config: atlas.config.AtlasConfig): DatasetPaths = {
    val c = config.receita
    val bronze = c.bronzeDir.stripSuffix("/").stripSuffix("\\")
    val silver = c.silverDir.stripSuffix("/").stripSuffix("\\")
    val status = java.nio.file.Paths.get(config.statusDir)
    val atlasRoot = Option(status.getParent).getOrElse(java.nio.file.Paths.get("data/_atlas"))
    val quality = atlasRoot.resolve("quality/receita/establishments").resolve(c.snapshot)
    DatasetPaths(
      s"$bronze/estabelecimentos",
      s"$silver/establishments",
      s"$silver/establishments_quality_report.json",
      s"$silver/establishments_quality_report.md",
      quality.resolve("malformed_rows").toString,
      quality.resolve("duplicate_cnpj_full").toString
    )
  }
}
