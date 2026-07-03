package atlas.io

import atlas.config.CsvConfig
import atlas.schema.EstabelecimentosSchema
import org.apache.spark.sql.{DataFrame, SparkSession}

object ReceitaCsvReader {
  def readEstabelecimentos(spark: SparkSession, path: String, csv: CsvConfig): DataFrame =
    spark.read.schema(EstabelecimentosSchema.raw)
      .option("header", "false")
      .option("sep", csv.delimiter)
      .option("encoding", csv.encoding)
      .option("mode", "PERMISSIVE")
      .csv(path)
}
