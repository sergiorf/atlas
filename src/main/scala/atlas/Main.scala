package atlas

import atlas.config.AppConfig
import atlas.etl.EstabelecimentosJob
import org.apache.spark.sql.SparkSession

object Main {
  final case class Cli(configPath: String = "conf/application.conf", sample: Boolean = false)

  def main(args: Array[String]): Unit = {
    val cli = parseArgs(args.toList)
    val config = AppConfig.load(cli.configPath, cli.sample)
    val spark = SparkSession
      .builder()
      .appName(config.spark.appName)
      .master(config.spark.master)
      .config("spark.sql.shuffle.partitions", config.spark.shufflePartitions)
      .getOrCreate()

    try EstabelecimentosJob.run(spark, config)
    finally spark.stop()
  }

  private def parseArgs(args: List[String]): Cli = args match {
    case Nil                         => Cli()
    case "--sample" :: tail         => parseArgs(tail).copy(sample = true)
    case "--config" :: path :: tail => parseArgs(tail).copy(configPath = path)
    case unknown :: _ =>
      throw new IllegalArgumentException(
        s"Unknown or incomplete argument: $unknown. Usage: [--config PATH] [--sample]"
      )
  }
}
