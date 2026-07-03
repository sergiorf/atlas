package atlas.config

import com.typesafe.config.{Config, ConfigFactory}

final case class CsvConfig(delimiter: String, encoding: String)
final case class SparkConfig(master: String, appName: String, shufflePartitions: Int)
final case class SampleConfig(enabled: Boolean, maxRows: Int)
final case class OutputConfig(baseDirectory: String, mode: String)
final case class AppConfig(
    inputGlob: String,
    output: OutputConfig,
    csv: CsvConfig,
    spark: SparkConfig,
    sample: SampleConfig
)

object AppConfig {
  def load(path: String, forceSample: Boolean): AppConfig =
    fromConfig(
      ConfigFactory.parseFile(new java.io.File(path)).resolve().getConfig("atlas-etl"),
      forceSample
    )

  private[config] def fromConfig(config: Config, forceSample: Boolean): AppConfig =
    AppConfig(
      config.getString("input.estabelecimentos-glob"),
      OutputConfig(config.getString("output.base-directory"), config.getString("output.mode")),
      CsvConfig(config.getString("csv.delimiter"), config.getString("csv.encoding")),
      SparkConfig(
        config.getString("spark.master"),
        config.getString("spark.app-name"),
        config.getInt("spark.shuffle-partitions")
      ),
      SampleConfig(
        forceSample || config.getBoolean("sample.enabled"),
        config.getInt("sample.max-rows")
      )
    )
}
