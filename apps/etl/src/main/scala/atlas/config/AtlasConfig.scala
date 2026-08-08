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
final case class GraphConfig(maxComponentPropagationRounds: Int = 128) {
  require(maxComponentPropagationRounds >= 1,
    "graph.max-component-propagation-rounds must be at least 1")
}
final case class StorageCleanupConfig(
    olderThanDays: Int = 7,
    workOlderThanDays: Int = 2,
    retainBundles: Int = 2,
    retainBronzeReleases: Int = 1
) {
  require(olderThanDays >= 0, "storage.cleanup.older-than-days must be non-negative")
  require(workOlderThanDays >= 0, "storage.cleanup.work-older-than-days must be non-negative")
  require(retainBundles >= 2, "storage.cleanup.retain-bundles must be at least 2")
  require(retainBronzeReleases >= 0, "storage.cleanup.retain-bronze-releases must be non-negative")
}
final case class AtlasConfig(
    spark: SparkConfig,
    csv: CsvConfig,
    receita: ReceitaConfig,
    statusDir: String,
    writeMode: String,
    graph: GraphConfig = GraphConfig(),
    storageCleanup: StorageCleanupConfig = StorageCleanupConfig()
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
      c.getString("output.write-mode"),
      GraphConfig(
        if (c.hasPath("graph.max-component-propagation-rounds"))
          c.getInt("graph.max-component-propagation-rounds")
        else 128
      ),
      StorageCleanupConfig(
        if (c.hasPath("storage.cleanup.older-than-days"))
          c.getInt("storage.cleanup.older-than-days")
        else 7,
        if (c.hasPath("storage.cleanup.work-older-than-days"))
          c.getInt("storage.cleanup.work-older-than-days")
        else 2,
        if (c.hasPath("storage.cleanup.retain-bundles"))
          c.getInt("storage.cleanup.retain-bundles")
        else 2,
        if (c.hasPath("storage.cleanup.retain-bronze-releases"))
          c.getInt("storage.cleanup.retain-bronze-releases")
        else 1
      )
    )
  }
}
