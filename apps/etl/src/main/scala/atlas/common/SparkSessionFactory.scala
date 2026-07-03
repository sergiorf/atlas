package atlas.common

import atlas.config.SparkConfig
import org.apache.spark.sql.SparkSession

object SparkSessionFactory {
  def create(c: SparkConfig): SparkSession = SparkSession.builder().appName(c.appName).master(c.master)
    .config("spark.sql.shuffle.partitions", c.shufflePartitions).getOrCreate()
}
