package atlas.config

import com.typesafe.config.ConfigFactory
import java.io.File

final case class SparkConfig(
    master: String,
    appName: String,
    shufflePartitions: Int,
    localDir: String
)
final case class CsvConfig(delimiter: String, encoding: String)
final case class ReceitaConfig(
    snapshot: String,
    rawDir: String,
    bronzeDir: String,
    silverDir: String,
    companyDataRawDir: String = ""
)
final case class AtlasConfig(
    spark: SparkConfig,
    csv: CsvConfig,
    receita: ReceitaConfig,
    statusDir: String,
    writeMode: String
)

object AtlasConfig {
  def load(path: String): AtlasConfig = {
    val c = ConfigFactory.parseFile(new File(path)).resolve().getConfig("atlas")
    AtlasConfig(
      SparkConfig(
        c.getString("spark.master"),
        c.getString("spark.app-name"),
        c.getInt("spark.shuffle-partitions"),
        c.getString("spark.local-dir")
      ),
      CsvConfig(c.getString("csv.delimiter"), c.getString("csv.encoding")),
      ReceitaConfig(
        c.getString("receita.snapshot"),
        c.getString("receita.raw-dir"),
        c.getString("receita.bronze-dir"),
        c.getString("receita.silver-dir"),
        if (c.hasPath("receita.company-data-raw-dir")) c.getString("receita.company-data-raw-dir") else ""
      ),
      c.getString("status-dir"),
      c.getString("output.write-mode")
    )
  }
}
