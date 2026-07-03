package atlas.config

import com.typesafe.config.ConfigFactory
import java.io.File

final case class SparkConfig(master: String, appName: String, shufflePartitions: Int)
final case class CsvConfig(delimiter: String, encoding: String)
final case class ReceitaConfig(rawDir: String, bronzeDir: String)
final case class AtlasConfig(spark: SparkConfig, csv: CsvConfig, receita: ReceitaConfig, writeMode: String)

object AtlasConfig {
  def load(path: String): AtlasConfig = {
    val c = ConfigFactory.parseFile(new File(path)).resolve().getConfig("atlas")
    AtlasConfig(
      SparkConfig(c.getString("spark.master"), c.getString("spark.app-name"), c.getInt("spark.shuffle-partitions")),
      CsvConfig(c.getString("csv.delimiter"), c.getString("csv.encoding")),
      ReceitaConfig(c.getString("receita.raw-dir"), c.getString("receita.bronze-dir")),
      c.getString("output.write-mode")
    )
  }
}
