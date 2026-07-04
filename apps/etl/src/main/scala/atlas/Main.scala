package atlas

import atlas.common.SparkSessionFactory
import atlas.config.AtlasConfig
import atlas.receita.ReceitaIngestJob
import atlas.receita.SilverEstablishmentJob

object Main {
  private[atlas] final case class Cli(command: String, configPath: String = "conf/application.conf")
  def main(args: Array[String]): Unit = {
    val cli = parseArgs(args.toList)
    val config = AtlasConfig.load(cli.configPath)
    val spark = SparkSessionFactory.create(config.spark)
    try cli.command match {
      case "ingest-receita-estabelecimentos" =>
        val result = ReceitaIngestJob.run(spark, config)
        println(s"Ingested ${result.rowCount} rows to ${result.outputPath}")
      case "normalize-receita-estabelecimentos" =>
        val result = SilverEstablishmentJob.run(spark, config)
        println(s"Normalized ${result.rowCount} rows to ${result.outputPath}")
      case unknown => throw new IllegalArgumentException(s"Unknown command: $unknown")
    } finally spark.stop()
  }
  private[atlas] def parseArgs(args: List[String]): Cli = args match {
    case command :: "--config" :: path :: Nil => Cli(command, path)
    case command :: Nil => Cli(command)
    case _ => throw new IllegalArgumentException(
      "Usage: atlas.Main <ingest-receita-estabelecimentos|normalize-receita-estabelecimentos> [--config PATH]"
    )
  }
}
