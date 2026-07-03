package atlas

import org.apache.spark.sql.SparkSession
import org.scalatest.{BeforeAndAfterAll, Suite}
trait SparkSuite extends BeforeAndAfterAll { this: Suite =>
  protected var spark: SparkSession = _
  override protected def beforeAll(): Unit = { super.beforeAll(); spark = SparkSession.builder().master("local[1]").appName("atlas-tests").getOrCreate(); spark.sparkContext.setLogLevel("ERROR") }
  override protected def afterAll(): Unit = { if (spark != null) spark.stop(); super.afterAll() }
}
