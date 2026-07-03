package atlas.io

import org.apache.spark.sql.DataFrame

object ParquetWriter {
  def writeEstabelecimentos(data: DataFrame, path: String, mode: String): Unit =
    data.write.mode(mode).partitionBy("uf").parquet(path)
}
