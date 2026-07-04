package atlas.common

import atlas.config.SparkConfig
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class SparkSessionFactoryTest extends AnyFunSuite {
  test("configures Spark local storage from Atlas configuration") {
    val localDir = Files.createTempDirectory("atlas-spark-local").toString
    val spark = SparkSessionFactory.create(SparkConfig("local[1]", "atlas-local-dir-test", 1, localDir))

    try assert(spark.sparkContext.getConf.get("spark.local.dir") === localDir)
    finally spark.stop()
  }
}
