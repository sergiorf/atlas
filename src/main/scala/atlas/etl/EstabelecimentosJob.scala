package atlas.etl

import atlas.config.AppConfig
import atlas.io.{ParquetWriter, ReceitaCsvReader}
import atlas.transform.EstabelecimentosTransformer
import org.apache.spark.sql.SparkSession

object EstabelecimentosJob {
  def run(spark: SparkSession, config: AppConfig): Unit = {
    val input = ReceitaCsvReader.readEstabelecimentos(spark, config.inputGlob, config.csv)
    val selected = if (config.sample.enabled) input.limit(config.sample.maxRows) else input
    val output = EstabelecimentosTransformer.transform(selected)
    val outputPath = s"${config.output.baseDirectory.stripSuffix("/")}/estabelecimentos"
    ParquetWriter.writeEstabelecimentos(output, outputPath, config.output.mode)
  }
}
